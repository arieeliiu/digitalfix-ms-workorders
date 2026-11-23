package cl.digitalfix.workorders;

import java.time.Instant;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import cl.digitalfix.workorders.messaging.ContratosMensajeria.Sobre;
import cl.digitalfix.workorders.messaging.kafka.EventoOrden;
import cl.digitalfix.workorders.messaging.kafka.CoordinadorEventosOrden;
import cl.digitalfix.workorders.messaging.kafka.PublicadorKafka;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Simula la transacción a mano: initSynchronization abre el "contexto" y
// afterCommit / afterCompletion imitan lo que Spring hace al confirmar o revertir.
class CoordinadorEventosOrdenTests {
    PublicadorKafka publicador = mock(PublicadorKafka.class);

    EventoOrden evento() {
        return new EventoOrden(42L, "CAMBIO_ESTADO", "CREADA", "ASIGNADA", "tecnico", "oid-operador",
                "Operador", Instant.now());
    }

    void commit() { TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit); }

    @AfterEach void limpiar() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test void publicaSoloDespuesDelCommitConKeyOrdenIdYTipoEnPasado() {
        when(publicador.publicar(eq(42L), any())).thenReturn(new RecordMetadata(new TopicPartition("workorders.events", 0), 3, 0, 0, 0, 0));
        TransactionSynchronizationManager.initSynchronization();

        new CoordinadorEventosOrden(publicador, true).despuesDelCommit(evento());
        verifyNoInteractions(publicador);   // dentro de la transacción todavía no sale nada

        commit();
        var sobre = ArgumentCaptor.forClass(Sobre.class);
        verify(publicador).publicar(eq(42L), sobre.capture());
        assertEquals("ORDEN_ESTADO_CAMBIADO", sobre.getValue().type());
        assertEquals("42", sobre.getValue().correlationId());
        assertEquals(evento().estadoNuevo(), ((EventoOrden) sobre.getValue().payload()).estadoNuevo());
    }

    @Test void rollbackNoPublica() {
        TransactionSynchronizationManager.initSynchronization();
        new CoordinadorEventosOrden(publicador, true).despuesDelCommit(evento());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(publicador);
    }

    @Test void falloDeKafkaNoSePropaga() {
        when(publicador.publicar(any(), any())).thenThrow(new PublicadorKafka.PublicacionFallida("kafka_TimeoutException"));
        TransactionSynchronizationManager.initSynchronization();
        new CoordinadorEventosOrden(publicador, true).despuesDelCommit(evento());
        assertDoesNotThrow(this::commit);
    }

    @Test void apagadoNoPublicaNiExigeTransaccion() {
        new CoordinadorEventosOrden(publicador, false).despuesDelCommit(evento());
        verifyNoInteractions(publicador);
    }

    @Test void encendidoSinTransaccionFalla() {
        assertThrows(IllegalStateException.class, () -> new CoordinadorEventosOrden(publicador, true).despuesDelCommit(evento()));
    }

    @Test void cadaAccionDelHistorialTieneSuTipo() {
        assertEquals("ORDEN_CREADA", new EventoOrden(1L, "CREACION", null, "CREADA", null, "a", null, Instant.now()).tipo());
        assertEquals("ORDEN_ELIMINADA", new EventoOrden(1L, "ELIMINACION", "CANCELADA", null, null, "a", null, Instant.now()).tipo());
    }
}
