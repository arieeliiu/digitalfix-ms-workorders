package cl.digitalfix.workorders.messaging.kafka;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import cl.digitalfix.workorders.messaging.ContratosMensajeria.Sobre;

// Coordina CUÁNDO se publica un EventoOrden (después del commit) y qué hacer si falla.
// El QUÉ es EventoOrden y el CÓMO es PublicadorKafka.
// - Solo después del commit: un rollback no deja eventos fantasma en Kafka.
// - Un fallo de Kafka se registra en el log y nunca se propaga: la orden ya está guardada.
@Component
public class CoordinadorEventosOrden {
    private static final Logger log = LoggerFactory.getLogger(CoordinadorEventosOrden.class);
    private final PublicadorKafka publicador;
    private final boolean habilitado;

    public CoordinadorEventosOrden(PublicadorKafka publicador,
            @Value("${digitalfix.kafka.enabled:false}") boolean habilitado) {
        this.publicador = publicador; this.habilitado = habilitado;
        log.info("eventos_kafka_workorders habilitado={}", habilitado);
    }

    // Se llama dentro de la transacción del cambio; la publicación queda en espera hasta el commit.
    public void despuesDelCommit(EventoOrden evento) {
        if (!habilitado) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Publicación de eventos exige una transacción activa");
        // Se arma ahora (valores inmutables) y se envía después.
        var sobre = new Sobre("1.0", evento.tipo(), UUID.randomUUID(), evento.fecha(), UUID.randomUUID(),
                evento.ordenId().toString(), evento);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    var metadata = publicador.publicar(evento.ordenId(), sobre);
                    log.info("evento resultado=publicado tipo={} eventId={} ordenId={} particion={} offset={}",
                            sobre.type(), sobre.eventId(), evento.ordenId(), metadata.partition(), metadata.offset());
                } catch (Exception e) {
                    // Nunca propagar: la respuesta HTTP de una orden ya confirmada no depende de Kafka.
                    log.warn("evento resultado=fallido tipo={} eventId={} ordenId={} motivo={}",
                            sobre.type(), sobre.eventId(), evento.ordenId(),
                            e instanceof PublicadorKafka.PublicacionFallida ? e.getMessage() : e.getClass().getSimpleName());
                }
            }
        });
    }
}
