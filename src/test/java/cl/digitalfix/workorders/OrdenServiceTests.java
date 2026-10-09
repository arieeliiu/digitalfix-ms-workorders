package cl.digitalfix.workorders;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import cl.digitalfix.workorders.client.CatalogoClient;
import cl.digitalfix.workorders.dto.request.*;
import cl.digitalfix.workorders.entity.EstadoOrden;
import cl.digitalfix.workorders.service.OrdenService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrdenServiceTests {
    @Autowired OrdenService servicio;
    @MockitoBean CatalogoClient catalog;

    @Test
    void asignacionReintentoYLecturaDeRepuestosMantienenContrato() {
        var orden = servicio.crearOrden(new NuevaOrdenRequest(1L, "Revisión", "Calle", "cliente"));
        var repuestos = List.of(new RepuestoRequest(4L, 2));
        var solicitud = new CambioEstadoRequest(EstadoOrden.ASIGNADA, " tecnico-1 ", repuestos);
        var asignada = servicio.cambiarEstado(orden.id(), solicitud, "operador", true);
        assertEquals("ASIGNADA", asignada.estado());
        assertEquals("tecnico-1", asignada.tecnicoId());
        assertEquals("operador", asignada.actualizadoPor());
        assertEquals(4L, asignada.repuestos().getFirst().repuestoId());
        assertEquals(2, asignada.repuestos().getFirst().cantidad());
        // H2 redondea los timestamps al persistir; comparar dos lecturas persistidas.
        var persistida = servicio.consultarOrden(orden.id());
        var reintento = servicio.cambiarEstado(orden.id(), solicitud, "operador", true);
        assertEquals(persistida, reintento);
        verify(catalog, times(1)).descontarStock(orden.id(), repuestos);
        // Se lee fuera de la transacción: el DTO no depende de la sesión JPA.
        assertEquals(persistida, servicio.consultarOrden(orden.id()));
        assertTrue(servicio.listarOrdenesDelSolicitante("cliente").stream()
                .anyMatch(leida -> leida.equals(persistida)));
    }

    @Test
    void transicionInvalidaYOrdenAjenaMantienenCodigos() {
        var orden = servicio.crearOrden(new NuevaOrdenRequest(1L, "Revisión", "Calle", "cliente"));
        var error = assertThrows(ResponseStatusException.class, () -> servicio.cambiarEstado(
                orden.id(), new CambioEstadoRequest(EstadoOrden.EN_EJECUCION, "tecnico", null), "operador", true));
        assertEquals(409, error.getStatusCode().value());
        var ajena = assertThrows(ResponseStatusException.class, () -> servicio.eliminarOrden(orden.id(), "otro"));
        assertEquals(404, ajena.getStatusCode().value());
        verifyNoInteractions(catalog);
    }

    @Test
    void cancelarYEliminarConservanSemanticaActual() {
        var orden = servicio.crearOrden(new NuevaOrdenRequest(1L, "Revisión", "Calle", "cliente"));
        var cancelada = servicio.cambiarEstado(orden.id(),
                new CambioEstadoRequest(EstadoOrden.CANCELADA, null, null), "cliente", false);
        assertEquals("CANCELADA", cancelada.estado());
        servicio.eliminarOrden(orden.id(), "cliente");
        var error = assertThrows(ResponseStatusException.class, () -> servicio.consultarOrden(orden.id()));
        assertEquals(404, error.getStatusCode().value());
        verifyNoInteractions(catalog);
    }
}
