package cl.digitalfix.workorders;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import cl.digitalfix.workorders.controller.OrdenController;
import cl.digitalfix.workorders.service.OrdenService;
import cl.digitalfix.workorders.repository.OrdenRepository;
import cl.digitalfix.workorders.dto.request.NuevaOrdenRequest;
import cl.digitalfix.workorders.entity.EstadoOrden;
import cl.digitalfix.workorders.client.CatalogoClient;
import cl.digitalfix.workorders.messaging.PublicadorRabbit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "digitalfix.rabbit.enabled=true")
class EdicionAdminTests {
    @Autowired OrdenService servicio;
    @Autowired OrdenRepository repositorio;
    @Autowired PlatformTransactionManager transacciones;
    @MockitoBean CatalogoClient catalog;
    @MockitoBean PublicadorRabbit publicador;
    MockMvc mvc;
    static final String DATOS = "{\"servicioId\":2,\"descripcion\":\"Cambio\",\"direccion\":\"Nueva\",\"admin\":true}";

    @BeforeEach void preparar() {
        mvc = MockMvcBuilders.standaloneSetup(new OrdenController(servicio)).build();
    }

    JwtAuthenticationToken token(String rol, String oid) {
        var builder = Jwt.withTokenValue("local").header("alg", "RS256").claim("roles", List.of(rol));
        if (oid != null) builder.claim("oid", oid);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }

    long orden(String estado) {
        var orden = servicio.crearOrden(new NuevaOrdenRequest(1L, "Original", "Calle", "propietario", "cliente@example.org"));
        new TransactionTemplate(transacciones).executeWithoutResult(tx -> {
            var entidad = repositorio.findById(orden.id()).orElseThrow();
            entidad.cambiarEstado(EstadoOrden.valueOf(estado), null, "propietario");
        });
        return orden.id();
    }

    @ParameterizedTest @ValueSource(strings = {"Admin", "Operador", "Cliente"})
    void rolesNoPuedenSuplantarPropietario(String rol) throws Exception {
        long id = orden("CREADA");
        var auth = token(rol, "otro");
        mvc.perform(put("/api/workorders/{id}", id).principal(auth).param("solicitanteId", "propietario")
                .param("rol", "Admin").contentType("application/json").content(DATOS))
                .andExpect(status().is(rol.equals("Admin") ? 200 : 404));
        var actual = servicio.consultarOrden(id);
        assertEquals("propietario", actual.solicitanteId());
        assertEquals(rol.equals("Admin") ? "otro" : "propietario", actual.actualizadoPor());
        mvc.perform(delete("/api/workorders/{id}", id).principal(auth).param("solicitanteId", "propietario")
                .param("admin", "true")).andExpect(status().is(rol.equals("Admin") ? 204 : 404));
        verifyNoInteractions(publicador, catalog);
    }

    @ParameterizedTest @ValueSource(strings = {"Admin", "Operador", "Cliente"})
    void propietarioConservaPermisosYTodosRespetanEstados(String rol) throws Exception {
        var auth = token(rol, rol.equals("Admin") ? "otro" : "propietario");
        for (EstadoOrden estado : EstadoOrden.values()) {
            long id = orden(estado.name());
            mvc.perform(put("/api/workorders/{id}", id).principal(auth).contentType("application/json").content(DATOS))
                    .andExpect(status().is(estado == EstadoOrden.CREADA ? 200 : 409));
            mvc.perform(delete("/api/workorders/{id}", id).principal(auth))
                    .andExpect(status().is(estado == EstadoOrden.CREADA || estado == EstadoOrden.CANCELADA ? 204 : 409));
        }
        verifyNoInteractions(publicador, catalog);
    }

    @Test void identidadYRolSonObligatorios() throws Exception {
        long id = orden("CREADA");
        mvc.perform(put("/api/workorders/{id}", id).param("solicitanteId", "propietario").contentType("application/json").content(DATOS))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/workorders/{id}", id).param("solicitanteId", "propietario")).andExpect(status().isUnauthorized());
        for (var auth : List.of(token("Admin", null), token("Admin", " "), token("Admin", "x".repeat(101)), token("Otro", "propietario"))) {
            mvc.perform(put("/api/workorders/{id}", id).principal(auth).contentType("application/json").content(DATOS)).andExpect(status().isForbidden());
            mvc.perform(delete("/api/workorders/{id}", id).principal(auth)).andExpect(status().isForbidden());
        }
        assertEquals("Original", servicio.consultarOrden(id).descripcion());
    }

    @Test void guardarDatosYCorregirCorreoNoPublicaNiCambiaEstado() throws Exception {
        long id = orden("CREADA");
        var auth = token("Admin", "admin");
        mvc.perform(put("/api/workorders/{id}", id).principal(auth).contentType("application/json").content(DATOS)).andExpect(status().isOk());
        assertEquals("cliente@example.org", servicio.consultarOrden(id).emailContacto());
        var antes = servicio.consultarOrden(id);
        for (String correo : List.of("nuevo@example.org", "otro@example.org")) {
            String valor = "\"" + correo + "\"";
            mvc.perform(patch("/api/workorders/{id}/contactos", id).principal(auth).contentType("application/json")
                    .content("{\"emailContacto\":" + valor + "}")).andExpect(status().isOk());
            var actual = servicio.consultarOrden(id);
            assertEquals(correo, actual.emailContacto());
            assertEquals(antes.estado(), actual.estado());
            assertEquals(antes.fechaActualizacion(), actual.fechaActualizacion());
            assertEquals(antes.repuestos(), actual.repuestos());
        }
        verifyNoInteractions(publicador, catalog);
    }

    @Test void creacionYAsignacionExigenCorreoAntesDeCualquierEfecto() throws Exception {
        long id = orden("CREADA");
        long cantidad = repositorio.count();
        for (String valor : List.of("omitido", "null", "\"\"", "\"   \"", "\"invalido\"", "\"a@\"", "\"" + "x".repeat(250) + "@example.org\"")) {
            String contacto = valor.equals("omitido") ? "" : ",\"emailContacto\":" + valor;
            mvc.perform(post("/api/workorders").contentType("application/json")
                    .content("{\"servicioId\":1,\"descripcion\":\"R\",\"direccion\":\"C\",\"solicitanteId\":\"propietario\"" + contacto + "}"))
                    .andExpect(status().isBadRequest());
            String tecnico = valor.equals("omitido") ? "" : ",\"emailTecnico\":" + valor;
            mvc.perform(put("/api/workorders/{id}/status", id).principal(token("Admin", "admin")).param("solicitanteId", "admin")
                    .contentType("application/json").content("{\"status\":\"ASIGNADA\",\"tecnicoId\":\"tecnico\",\"repuestos\":[{\"repuestoId\":1,\"cantidad\":1}]" + tecnico + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(cantidad, repositorio.count());
        assertEquals("CREADA", servicio.consultarOrden(id).estado());
        assertTrue(servicio.consultarOrden(id).repuestos().isEmpty());
        verifyNoInteractions(publicador, catalog);
    }

    @Test void antiguaSinContactosAdmiteConsultaPutYCorreccionSinNotificar() throws Exception {
        long id = orden("CREADA");
        new TransactionTemplate(transacciones).executeWithoutResult(tx -> repositorio.findById(id).orElseThrow().actualizarContactos("", null));
        var auth = token("Cliente", "propietario");
        mvc.perform(get("/api/workorders/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.emailContacto").isEmpty());
        mvc.perform(put("/api/workorders/{id}", id).principal(auth).contentType("application/json").content(DATOS)).andExpect(status().isOk());
        assertNull(servicio.consultarOrden(id).emailContacto());
        mvc.perform(patch("/api/workorders/{id}/contactos", id).principal(auth).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        assertNull(servicio.consultarOrden(id).emailContacto());
        mvc.perform(patch("/api/workorders/{id}/contactos", id).principal(auth).contentType("application/json")
                .content("{\"emailContacto\":\"completado@example.org\"}")).andExpect(status().isOk());
        assertEquals("completado@example.org", servicio.consultarOrden(id).emailContacto());
        verifyNoInteractions(publicador, catalog);
    }

    @Test void patchDistingueOmisionDeBorradoYConservaTecnicoAsignado() throws Exception {
        long id = orden("ASIGNADA");
        new TransactionTemplate(transacciones).executeWithoutResult(tx -> {
            var entidad = repositorio.findById(id).orElseThrow();
            entidad.cambiarEstado(EstadoOrden.ASIGNADA, "tecnico", "admin");
            entidad.actualizarContactos(null, "tecnico@example.org");
        });
        var auth = token("Admin", "admin");
        var antes = servicio.consultarOrden(id);
        for (String campo : List.of("emailContacto", "emailTecnico")) {
            for (String valor : List.of("null", "\"\"", "\"   \"", "\"invalido\"")) {
                mvc.perform(patch("/api/workorders/{id}/contactos", id).principal(auth).contentType("application/json")
                        .content("{\"" + campo + "\":" + valor + "}")).andExpect(status().isBadRequest());
                assertEquals(antes, servicio.consultarOrden(id));
            }
        }
        mvc.perform(patch("/api/workorders/{id}/contactos", id).principal(auth).contentType("application/json")
                .content("{\"emailContacto\":\"nuevo@example.org\"}")).andExpect(status().isOk());
        assertEquals(antes.emailTecnico(), servicio.consultarOrden(id).emailTecnico());
        mvc.perform(patch("/api/workorders/{id}/contactos", id).principal(auth).contentType("application/json")
                .content("{\"emailTecnico\":\"otro@example.org\"}")).andExpect(status().isOk());
        assertEquals("nuevo@example.org", servicio.consultarOrden(id).emailContacto());
        assertEquals(antes.estado(), servicio.consultarOrden(id).estado());
        assertEquals(antes.fechaActualizacion(), servicio.consultarOrden(id).fechaActualizacion());
        // La orden ya está asignada: el reintento sin correo no descuenta ni publica.
        mvc.perform(put("/api/workorders/{id}/status", id).principal(auth).param("solicitanteId", "admin")
                .contentType("application/json").content("{\"status\":\"ASIGNADA\"}")).andExpect(status().isOk());
        verifyNoInteractions(publicador, catalog);
    }
}
