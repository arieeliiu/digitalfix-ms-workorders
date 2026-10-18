package cl.digitalfix.workorders.messaging;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import cl.digitalfix.workorders.messaging.ContratosMensajeria.Comando;

@Component
public class PublicadorRabbit {
    private final RabbitTemplate template;
    private final Duration timeout;
    private final JsonMapper json = JsonMapper.builder().build();

    public PublicadorRabbit(RabbitTemplate template,
            @Value("${digitalfix.rabbit.confirm-timeout:5s}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Timeout de confirms debe estar entre 1 ms y 30 s");
        this.template = template; this.timeout = timeout;
    }

    public void publicar(Comando comando) {
        var sobre = comando.sobre();
        byte[] cuerpo = json.writeValueAsBytes(sobre);
        int limite = sobre.type().equals("GENERAR_REPORTE") ? 262144 : 65536;
        if (cuerpo.length > limite) throw new IllegalArgumentException("mensaje_excede_limite_consumidor");
        var propiedades = new MessageProperties();
        propiedades.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        propiedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propiedades.setContentEncoding("UTF-8");
        propiedades.setMessageId(sobre.eventId().toString());
        propiedades.setCorrelationId(sobre.correlationId());
        var correlacion = new CorrelationData(sobre.eventId().toString());
        try {
            template.send(comando.exchange(), comando.routingKey(), new Message(cuerpo, propiedades), correlacion);
            var confirmacion = correlacion.getFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            // Spring entrega el retorno correlacionado antes de completar el futuro de confirm.
            if (correlacion.getReturned() != null) throw new PublicacionFallida("mensaje_retornado_sin_ruta");
            if (!confirmacion.ack()) throw new PublicacionFallida("broker_nack");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new PublicacionFallida("publicacion_interrumpida");
        } catch (PublicacionFallida e) { throw e; }
        catch (java.util.concurrent.TimeoutException e) { throw new PublicacionFallida("confirm_timeout_resultado_incierto"); }
        catch (Exception e) { throw new PublicacionFallida("broker_" + e.getClass().getSimpleName()); }
    }

    public static class PublicacionFallida extends RuntimeException {
        public PublicacionFallida(String motivo) { super(motivo); }
    }
}
