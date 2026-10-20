package cl.digitalfix.workorders;

import java.util.List;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import cl.digitalfix.workorders.client.CatalogoClient;
import cl.digitalfix.workorders.dto.request.*;
import cl.digitalfix.workorders.dto.response.OrdenResponse;
import cl.digitalfix.workorders.entity.EstadoOrden;
import cl.digitalfix.workorders.messaging.PublicadorRabbit;
import cl.digitalfix.workorders.messaging.ContratosMensajeria.*;
import cl.digitalfix.workorders.repository.OrdenRepository;
import cl.digitalfix.workorders.service.OrdenService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,
        properties="digitalfix.rabbit.enabled=true")
class MensajeriaTransaccionesTests {
    @Autowired OrdenService servicio;
    @Autowired OrdenRepository repositorio;
    @Autowired PlatformTransactionManager transacciones;
    @MockitoBean CatalogoClient catalog;
    @MockitoBean PublicadorRabbit publicador;

    OrdenResponse crear() {
        return servicio.crearOrden(new NuevaOrdenRequest(2L,"Revisión eléctrica","Ñuñoa","cliente","cliente@example.org"));
    }
    OrdenResponse asignar(Long id) {
        when(catalog.nombreServicio(2L)).thenReturn("Revisión eléctrica");
        return servicio.cambiarEstado(id,new CambioEstadoRequest(EstadoOrden.ASIGNADA,"tecnico",List.of(new RepuestoRequest(4L,2)),"tecnico@example.org"),"operador",true);
    }
    OrdenResponse estado(Long id, EstadoOrden e) {
        return servicio.cambiarEstado(id,new CambioEstadoRequest(e,null,null),"operador",true);
    }
    List<Comando> enviados(int cantidad) {
        var captor = ArgumentCaptor.forClass(Comando.class);
        verify(publicador,times(cantidad)).publicar(captor.capture());
        return captor.getAllValues();
    }

    @Test void publicaSoloDespuesDelCommitConInstantanea() {
        var orden = crear();
        new TransactionTemplate(transacciones).executeWithoutResult(s -> {
            asignar(orden.id()); verifyNoInteractions(publicador);
        });
        var comandos = enviados(2);
        assertEquals("ORDEN_ASIGNADA",comandos.getFirst().sobre().type());
        assertEquals("DESPACHO_TECNICO",comandos.getLast().sobre().type());
        assertEquals(comandos.getFirst().sobre().traceId(),comandos.getLast().sobre().traceId());
        assertNotEquals(comandos.getFirst().sobre().eventId(),comandos.getLast().sobre().eventId());
        assertEquals(new Despacho(orden.id(),"tecnico@example.org","tecnico",2L,"Revisión eléctrica","Ñuñoa"),comandos.getLast().sobre().payload());
        verify(catalog).descontarStock(orden.id(),List.of(new RepuestoRequest(4L,2)));
    }
    @Test void rollbackNoPublicaNiConfirmaEstado() {
        var orden = crear();
        new TransactionTemplate(transacciones).executeWithoutResult(s -> { asignar(orden.id()); s.setRollbackOnly(); });
        verifyNoInteractions(publicador);
        assertEquals("CREADA",servicio.consultarOrden(orden.id()).estado());
    }
    @Test void falloAlPersistirDespuesDePrepararNoPublica() {
        var orden = crear();
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(transacciones).executeWithoutResult(s -> {
            asignar(orden.id());
            ReflectionTestUtils.setField(repositorio.findById(orden.id()).orElseThrow(),"descripcion",null);
        }));
        verifyNoInteractions(publicador);
        assertEquals("CREADA",servicio.consultarOrden(orden.id()).estado());
    }
    @Test void falloCatalogNoPublica() {
        var orden = crear();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT)).when(catalog).descontarStock(any(),any());
        assertThrows(ResponseStatusException.class, () -> asignar(orden.id()));
        verifyNoInteractions(publicador);
        assertEquals("CREADA",servicio.consultarOrden(orden.id()).estado());
    }
    @Test void falloRabbitPosteriorNoRevierteNiPropagaYContinuaSegundoComando() {
        var orden = crear();
        doThrow(new IllegalStateException("broker caído")).when(publicador).publicar(any());
        assertEquals("ASIGNADA",asignar(orden.id()).estado());
        assertEquals("ASIGNADA",servicio.consultarOrden(orden.id()).estado());
        verify(publicador,times(2)).publicar(any());
    }
    @Test void todasLasRutasYRepetirEstadoNoDuplicaNiDescuenta() {
        var orden = crear(); asignar(orden.id()); asignar(orden.id());
        estado(orden.id(),EstadoOrden.EN_DESPLAZAMIENTO);
        estado(orden.id(),EstadoOrden.EN_EJECUCION);
        var cerrada = estado(orden.id(),EstadoOrden.CERRADA);
        estado(orden.id(),EstadoOrden.CERRADA);
        var comandos = enviados(5);
        assertEquals(List.of("email.send","dispatch.ticket","email.en_camino","email.send","report.gen"),comandos.stream().map(Comando::routingKey).toList());
        assertEquals("cmd.topic",comandos.get(2).exchange());
        assertEquals(5,new HashSet<>(comandos.stream().map(c -> c.sobre().eventId()).toList()).size());
        assertEquals(comandos.get(3).sobre().traceId(),comandos.get(4).sobre().traceId());
        var informe = (Informe) comandos.get(4).sobre().payload();
        assertEquals(cerrada.fechaActualizacion(),informe.fechaCierre());
        assertEquals(cerrada.fechaCreacion(),informe.fechaCreacion());
        assertEquals(List.of(new Repuesto(4L,2)),informe.repuestos());
        assertEquals("CERRADA",informe.estado());
        assertEquals(orden.id().toString(),comandos.getFirst().sobre().correlationId());
        verify(catalog,times(1)).descontarStock(any(),any());
    }
    @Test void invalidaCanceladaYCreadaNoPublican() {
        var orden = crear();
        assertThrows(ResponseStatusException.class, () -> estado(orden.id(),EstadoOrden.EN_EJECUCION));
        estado(orden.id(),EstadoOrden.CREADA); estado(orden.id(),EstadoOrden.CANCELADA);
        verifyNoInteractions(publicador,catalog);
    }
    @Test void ordenAntiguaSinContactosContinuaYPublicaInformeSinRepuestos() {
        var antigua = new cl.digitalfix.workorders.entity.OrdenTrabajo(2L,"Revisión","Calle","cliente");
        var id = repositorio.saveAndFlush(antigua).getId();
        new TransactionTemplate(transacciones).executeWithoutResult(tx ->
            repositorio.findById(id).orElseThrow().cambiarEstado(EstadoOrden.ASIGNADA,"tecnico","operador"));
        var orden = servicio.consultarOrden(id);
        estado(orden.id(),EstadoOrden.EN_DESPLAZAMIENTO); estado(orden.id(),EstadoOrden.EN_EJECUCION); estado(orden.id(),EstadoOrden.CERRADA);
        var comandos = enviados(1);
        assertEquals("GENERAR_REPORTE",comandos.getFirst().sobre().type());
        assertTrue(((Informe)comandos.getFirst().sobre().payload()).repuestos().isEmpty());
        verifyNoInteractions(catalog);
    }
    @Test void falloNombreServicioNoBloqueaNiFabricaTicket() {
        var orden = crear();
        when(catalog.nombreServicio(2L)).thenThrow(new IllegalStateException("no disponible"));
        var respuesta = servicio.cambiarEstado(orden.id(),new CambioEstadoRequest(EstadoOrden.ASIGNADA,"tecnico",List.of(),"tecnico@example.org"),"operador",true);
        assertEquals("ASIGNADA",respuesta.estado());
        assertEquals("ORDEN_ASIGNADA",enviados(1).getFirst().sobre().type());
    }
    @Test void contactosAntiguosNoCambianEstadoFechaStockNiPublican() {
        var orden = crear(); asignar(orden.id());
        reset(publicador,catalog);
        var antes = servicio.consultarOrden(orden.id());
        var despues = servicio.actualizarContactos(orden.id(),new ContactosRequest("nuevo@example.org","otro@example.org"),"operador",true);
        assertEquals(antes.estado(),despues.estado()); assertEquals(antes.fechaActualizacion(),despues.fechaActualizacion());
        assertEquals(antes.repuestos(),despues.repuestos());
        assertEquals("nuevo@example.org",servicio.consultarOrden(orden.id()).emailContacto());
        assertEquals(404,assertThrows(ResponseStatusException.class, () -> servicio.actualizarContactos(orden.id(),new ContactosRequest("ajeno@example.org",null),"otro",false)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class, () -> servicio.actualizarContactos(orden.id(),new ContactosRequest(null,"tecnico@example.org"),"cliente",false)).getStatusCode().value());
        servicio.actualizarContactos(orden.id(),new ContactosRequest("propio@example.org",null),"cliente",false);
        verifyNoInteractions(publicador,catalog);
    }

    @Test void httpExitosoConBrokerFallidoYContactosExigenIdentidad() throws Exception {
        var orden = crear();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new cl.digitalfix.workorders.controller.OrdenController(servicio)).build();
        var jwt=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("prueba").header("alg","RS256")
                .claim("oid","operador").claim("roles",List.of("Operador")).build();
        var auth=new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt,List.of());
        doThrow(new IllegalStateException("sin broker")).when(publicador).publicar(any());
        when(catalog.nombreServicio(2L)).thenReturn("Revisión eléctrica");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/workorders/{id}/status",orden.id())
                .principal(auth).param("solicitanteId","operador").contentType("application/json")
                .content("{\"status\":\"ASIGNADA\",\"tecnicoId\":\"tecnico\",\"emailTecnico\":\"t@example.org\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals("ASIGNADA",servicio.consultarOrden(orden.id()).estado());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/workorders/{id}/contactos",orden.id())
                .contentType("application/json").content("{\"emailContacto\":\"nuevo@example.org\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/workorders/{id}/contactos",orden.id())
                .principal(auth).contentType("application/json").content("{\"emailContacto\":\"invalido\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/workorders/{id}/contactos",orden.id())
                .principal(auth).contentType("application/json").content("{\"emailContacto\":\"nuevo@example.org\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
}
