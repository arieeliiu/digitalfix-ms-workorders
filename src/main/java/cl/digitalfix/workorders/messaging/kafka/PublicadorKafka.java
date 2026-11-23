package cl.digitalfix.workorders.messaging.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import cl.digitalfix.workorders.messaging.ContratosMensajeria.Sobre;

// Única clase que habla con Kafka. Publica un evento y espera la confirmación del broker.
// No decide cuándo publicar ni qué hacer si falla: eso es de quien la llama.
@Component
public class PublicadorKafka {
    public static final String TOPICO = "workorders.events";
    public static final String TOPICO_EDICIONES = "audit.timeline";

    private final KafkaTemplate<String, String> template;
    private final Duration timeout;
    private final JsonMapper json = JsonMapper.builder().build();

    public PublicadorKafka(KafkaTemplate<String, String> template,
            @Value("${digitalfix.kafka.timeout:5s}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Timeout de Kafka debe estar entre 1 ms y 30 s");
        this.template = template; this.timeout = timeout;
    }

    // Key = ordenId: todos los eventos de una orden van a la misma partición y se leen en orden.
    public RecordMetadata publicar(Long ordenId, Sobre sobre) {
        if (ordenId == null) throw new IllegalArgumentException("ordenId es obligatorio como key");
        // Cada hecho tiene un solo destino: ciclo de vida o edición auditable.
        String topico = switch (sobre.type()) {
            case "ORDEN_CREADA", "ORDEN_ESTADO_CAMBIADO", "ORDEN_ELIMINADA" -> TOPICO;
            case "ORDEN_DATOS_ACTUALIZADOS", "ORDEN_CONTACTOS_ACTUALIZADOS", "ORDEN_REPUESTOS_ACTUALIZADOS" -> TOPICO_EDICIONES;
            default -> throw new IllegalArgumentException("Tipo de evento desconocido");
        };
        var registro = new ProducerRecord<String, String>(topico, ordenId.toString(), json.writeValueAsString(sobre));
        // Correlation ID en los headers para seguir el evento hasta el consumidor
        if (sobre.correlationId() != null)
            registro.headers().add("correlationId", sobre.correlationId().getBytes(StandardCharsets.UTF_8));
        try {
            // Con acks=all, el futuro se completa cuando el leader y los ISR confirmaron
            return template.send(registro).get(timeout.toMillis(), TimeUnit.MILLISECONDS).getRecordMetadata();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new PublicacionFallida("publicacion_interrumpida");
        } catch (TimeoutException e) {
            throw new PublicacionFallida("kafka_timeout_resultado_incierto");
        } catch (ExecutionException e) {
            throw new PublicacionFallida("kafka_" + e.getCause().getClass().getSimpleName());
        } catch (RuntimeException e) {
            // Kafka caído: send() falla al no poder obtener metadatos del tópico
            throw new PublicacionFallida("kafka_" + e.getClass().getSimpleName());
        }
    }

    public static class PublicacionFallida extends RuntimeException {
        public PublicacionFallida(String motivo) { super(motivo); }
    }
}
