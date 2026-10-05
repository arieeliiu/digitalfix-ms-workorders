package cl.digitalfix.workorders;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import cl.digitalfix.workorders.dto.request.CrearOrdenSolicitud;
import cl.digitalfix.workorders.service.OrdenTrabajoServicio;
import static org.junit.jupiter.api.Assertions.*;

class PersistenciaReinicioTests {
    @TempDir Path temporal;

    @Test
    void ordenPersisteTrasCerrarYReabrirAplicacion() {
        Long id;
        try (var contexto = iniciar()) {
            var orden = contexto.getBean(OrdenTrabajoServicio.class).crearOrden(
                new CrearOrdenSolicitud(1L, "Revisión", "Calle 123", "oid-prueba"));
            id = orden.id();
            assertNotNull(id);
            assertEquals("CREADA", orden.estado());
            assertNotNull(orden.fechaCreacion());
        }
        try (var contexto = iniciar()) {
            var servicio = contexto.getBean(OrdenTrabajoServicio.class);
            var orden = servicio.consultarOrden(id);
            assertEquals("oid-prueba", orden.solicitanteId());
            assertEquals("Revisión", orden.descripcion());
            assertEquals(1, servicio.listarOrdenesDelSolicitante("oid-prueba").size());
            assertTrue(servicio.listarOrdenesDelSolicitante("otro-oid").isEmpty());
        }
    }

    private ConfigurableApplicationContext iniciar() {
        return new SpringApplicationBuilder(DigitalfixMsWorkordersApplication.class)
            .web(WebApplicationType.NONE).run(
                "--spring.datasource.url=jdbc:h2:file:" + temporal.resolve("ordenes").toString().replace('\\', '/') + ";MODE=Oracle",
                "--spring.jpa.hibernate.ddl-auto=update");
    }
}
