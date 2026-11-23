package cl.digitalfix.workorders;

import java.util.List;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import cl.digitalfix.workorders.client.CatalogoClient;
import cl.digitalfix.workorders.dto.request.*;
import cl.digitalfix.workorders.dto.response.OrdenResponse;
import cl.digitalfix.workorders.entity.EstadoOrden;
import cl.digitalfix.workorders.messaging.ContratosMensajeria.Sobre;
import cl.digitalfix.workorders.messaging.kafka.EventoOrden;
import cl.digitalfix.workorders.messaging.kafka.PublicadorKafka;
import cl.digitalfix.workorders.service.OrdenService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Flujo real de OrdenService con H2: cada registro del historial produce un evento,
// que sale solo si la transacción se confirma. Kafka se simula con PublicadorKafka.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "digitalfix.kafka.enabled=true")
class EventosKafkaTransaccionesTests {
    @Autowired OrdenService servicio;
    @Autowired PlatformTransactionManager transacciones;
    @MockitoBean CatalogoClient catalog;
    @MockitoBean PublicadorKafka publicador;

    @BeforeEach void kafkaResponde() {
        when(publicador.publicar(any(), any()))
                .thenReturn(new RecordMetadata(new TopicPartition(PublicadorKafka.TOPICO, 0), 0, 0, 0, 0, 0));
    }

    OrdenResponse crear() {
        return servicio.crearOrden(new NuevaOrdenRequest(2L, "Revisión eléctrica", "Ñuñoa", "cliente", "cliente@example.org"));
    }
    OrdenResponse asignar(Long id) {
        return servicio.cambiarEstado(id, new CambioEstadoRequest(EstadoOrden.ASIGNADA, "tecnico",
                List.of(new RepuestoRequest(4L, 2)), "tecnico@example.org"), "operador", true);
    }
    List<Sobre> publicados(Long ordenId, int cantidad) {
        var captor = ArgumentCaptor.forClass(Sobre.class);
        verify(publicador, times(cantidad)).publicar(eq(ordenId), captor.capture());
        return captor.getAllValues();
    }

    // Construir el productor real valida su configuración sin conectarse a Kafka.
    @Test void laConfiguracionDelProductorRealEsValida(
            @Autowired org.springframework.kafka.core.ProducerFactory<String, String> fabrica) {
        assertDoesNotThrow(() -> fabrica.createProducer().close());
    }

    @Test void crearYAsignarPublicanUnEventoCadaUnoConKeyOrdenId() {
        var orden = crear();
        asignar(orden.id());
        var sobres = publicados(orden.id(), 2);
        assertEquals(List.of("ORDEN_CREADA", "ORDEN_ESTADO_CAMBIADO"), sobres.stream().map(Sobre::type).toList());
        var asignacion = (EventoOrden) sobres.getLast().payload();
        assertEquals("CREADA", asignacion.estadoAnterior());
        assertEquals("ASIGNADA", asignacion.estadoNuevo());
        assertEquals("tecnico", asignacion.tecnicoId());
        assertEquals(orden.id().toString(), sobres.getLast().correlationId());
    }

    @Test void rollbackNoPublicaElCambio() {
        var orden = crear();
        new TransactionTemplate(transacciones).executeWithoutResult(tx -> { asignar(orden.id()); tx.setRollbackOnly(); });
        assertEquals(List.of("ORDEN_CREADA"), publicados(orden.id(), 1).stream().map(Sobre::type).toList());
        assertEquals("CREADA", servicio.consultarOrden(orden.id()).estado());
    }

    @Test void falloDeCatalogNoPublicaElCambio() {
        var orden = crear();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT)).when(catalog).descontarStock(any(), any());
        assertThrows(ResponseStatusException.class, () -> asignar(orden.id()));
        assertEquals(List.of("ORDEN_CREADA"), publicados(orden.id(), 1).stream().map(Sobre::type).toList());
    }

    @Test void kafkaCaidoNoRevierteLaOrden() {
        when(publicador.publicar(any(), any())).thenThrow(new PublicadorKafka.PublicacionFallida("kafka_TimeoutException"));
        var orden = crear();
        assertEquals("ASIGNADA", asignar(orden.id()).estado());
        assertEquals("ASIGNADA", servicio.consultarOrden(orden.id()).estado());
    }

    @Test void repetirEstadoNoPublicaOtraVez() {
        var orden = crear();
        asignar(orden.id());
        asignar(orden.id());
        publicados(orden.id(), 2);
    }

    @Test void eliminarPublicaOrdenEliminada() {
        var orden = crear();
        servicio.eliminarOrden(orden.id(), "cliente", true);
        var sobres = publicados(orden.id(), 2);
        assertEquals("ORDEN_ELIMINADA", sobres.getLast().type());
        assertEquals("CREADA", ((EventoOrden) sobres.getLast().payload()).estadoAnterior());
    }
}
