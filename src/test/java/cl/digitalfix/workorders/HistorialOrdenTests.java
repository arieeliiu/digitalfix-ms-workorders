package cl.digitalfix.workorders;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import cl.digitalfix.workorders.client.CatalogoClient;
import cl.digitalfix.workorders.controller.OrdenController;
import cl.digitalfix.workorders.dto.request.CambioEstadoRequest;
import cl.digitalfix.workorders.dto.request.NuevaOrdenRequest;
import cl.digitalfix.workorders.dto.response.HistorialOrdenResponse;
import cl.digitalfix.workorders.entity.EstadoOrden;
import cl.digitalfix.workorders.service.ActorAuditoria;
import cl.digitalfix.workorders.service.HistorialOrdenService;
import cl.digitalfix.workorders.service.OrdenService;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Trazabilidad: creación, cambios de estado y eliminación quedan registrados
// en la misma transacción y el historial sobrevive a la orden.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HistorialOrdenTests {
    @Autowired OrdenService servicio;
    @Autowired HistorialOrdenService historial;
    @Autowired PlatformTransactionManager transacciones;
    @MockitoBean CatalogoClient catalog;
    MockMvc mvc;

    static final ActorAuditoria OPERADOR = new ActorAuditoria("oid-operador", "Operador");

    @BeforeEach void preparar() {
        mvc = MockMvcBuilders.standaloneSetup(new OrdenController(servicio)).build();
    }

    JwtAuthenticationToken token(String oid, String... roles) {
        var builder = Jwt.withTokenValue("local").header("alg", "RS256").claim("roles", List.of(roles));
        if (oid != null) builder.claim("oid", oid);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }

    long crear() {
        return servicio.crearOrden(new NuevaOrdenRequest(1L, "Revisión", "Calle", "oid-cliente", "cliente@example.org"), "Cliente").id();
    }

    void estado(long id, EstadoOrden estado) {
        var tecnico = estado == EstadoOrden.ASIGNADA ? "tecnico-1" : null;
        var correo = estado == EstadoOrden.ASIGNADA ? "tecnico@example.org" : null;
        servicio.cambiarEstado(id, new CambioEstadoRequest(estado, tecnico, null, correo), "oid-operador", true, OPERADOR);
    }

    @Test void cicloCompletoQuedaRegistradoEnOrden() {
        long id = crear();
        estado(id, EstadoOrden.ASIGNADA);
        estado(id, EstadoOrden.EN_DESPLAZAMIENTO);
        estado(id, EstadoOrden.EN_EJECUCION);
        estado(id, EstadoOrden.CERRADA);

        var registros = historial.consultar(id);
        assertEquals(5, registros.size());

        var creacion = registros.getFirst();
        assertEquals("CREACION", creacion.accion());
        assertNull(creacion.estadoAnterior());
        assertEquals("CREADA", creacion.estadoNuevo());
        assertEquals("oid-cliente", creacion.actorId());
        assertEquals("Cliente", creacion.actorRoles());

        assertEquals(List.of("CREADA>ASIGNADA", "ASIGNADA>EN_DESPLAZAMIENTO",
                "EN_DESPLAZAMIENTO>EN_EJECUCION", "EN_EJECUCION>CERRADA"),
                registros.stream().skip(1).map(r -> r.estadoAnterior() + ">" + r.estadoNuevo()).toList());
        registros.stream().skip(1).forEach(r -> {
            assertEquals("CAMBIO_ESTADO", r.accion());
            assertEquals("oid-operador", r.actorId());
            assertEquals("Operador", r.actorRoles());
            assertEquals("tecnico-1", r.tecnicoId());
            assertNotNull(r.fecha());
        });
    }

    @Test void reintentoDelMismoEstadoNoRegistra() {
        long id = crear();
        estado(id, EstadoOrden.ASIGNADA);
        estado(id, EstadoOrden.ASIGNADA);
        assertEquals(2, historial.consultar(id).size());
    }

    @Test void transicionRechazadaNoRegistra() {
        long id = crear();
        var error = assertThrows(ResponseStatusException.class, () -> estado(id, EstadoOrden.CERRADA));
        assertEquals(409, error.getStatusCode().value());
        assertEquals(List.of("CREACION"), historial.consultar(id).stream().map(HistorialOrdenResponse::accion).toList());
    }

    @Test void rollbackDelCambioNoDejaRegistro() {
        long id = crear();
        new TransactionTemplate(transacciones).executeWithoutResult(tx -> {
            estado(id, EstadoOrden.CANCELADA);
            tx.setRollbackOnly();
        });
        assertEquals("CREADA", servicio.consultarOrden(id).estado());
        assertEquals(1, historial.consultar(id).size());
    }

    @Test void registrarFueraDeTransaccionFalla() {
        assertThrows(IllegalTransactionStateException.class,
                () -> historial.registrarCambioEstado(1L, "CREADA", "ASIGNADA", null, "oid", null));
    }

    @Test void historialSobreviveALaEliminacion() throws Exception {
        long id = crear();
        estado(id, EstadoOrden.CANCELADA);
        mvc.perform(delete("/api/workorders/{id}", id).principal(token("oid-admin", "Admin")))
                .andExpect(status().isNoContent());

        assertThrows(ResponseStatusException.class, () -> servicio.consultarOrden(id));
        mvc.perform(get("/api/workorders/{id}/historial", id).principal(token("oid-auditor", "Auditor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].accion").value("ELIMINACION"))
                .andExpect(jsonPath("$[2].estadoAnterior").value("CANCELADA"))
                .andExpect(jsonPath("$[2].actorId").value("oid-admin"))
                .andExpect(jsonPath("$[2].actorRoles").value("Admin"));
    }

    @Test void actorDelHistorialSaleDelJwtYNoDelQueryParam() throws Exception {
        long id = crear();
        mvc.perform(put("/api/workorders/{id}/status", id)
                        .principal(token("oid-real", "Operador", "Admin"))
                        .param("solicitanteId", "otro-valor")
                        .contentType("application/json")
                        .content("{\"status\":\"CANCELADA\"}"))
                .andExpect(status().isOk());
        var cambio = historial.consultar(id).getLast();
        assertEquals("oid-real", cambio.actorId());
        assertEquals("Admin,Operador", cambio.actorRoles());
    }

    @ParameterizedTest
    @CsvSource({"Admin,200", "Auditor,200", "Operador,403", "Cliente,403"})
    void soloAdminYAuditorLeenElHistorial(String rol, int esperado) throws Exception {
        long id = crear();
        mvc.perform(get("/api/workorders/{id}/historial", id).principal(token("oid", rol)))
                .andExpect(status().is(esperado));
    }

    @Test void sinTokenResponde401YOrdenInexistente404() throws Exception {
        long id = crear();
        mvc.perform(get("/api/workorders/{id}/historial", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workorders/{id}/historial", 999_999L).principal(token("oid", "Admin")))
                .andExpect(status().isNotFound());
    }
}
