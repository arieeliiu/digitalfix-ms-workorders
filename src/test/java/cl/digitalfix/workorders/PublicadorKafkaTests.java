package cl.digitalfix.workorders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import cl.digitalfix.workorders.messaging.kafka.PublicadorKafka;
import cl.digitalfix.workorders.messaging.ContratosMensajeria.Sobre;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicadorKafkaTests {
    Sobre sobre() {
        return new Sobre("1.0", "ORDEN_CREADA", UUID.randomUUID(), Instant.now(), UUID.randomUUID(), "42",
                java.util.Map.of("ordenId", 42));
    }

    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> template() { return mock(KafkaTemplate.class); }

    @Test void publicaEnElTopicoConKeyOrdenIdJsonYCorrelationId() {
        var template = template();
        var captor = ArgumentCaptor.<ProducerRecord<String, String>>captor();
        var metadata = new RecordMetadata(new TopicPartition(PublicadorKafka.TOPICO, 1), 7, 0, 0, 0, 0);
        when(template.send(captor.capture()))
                .thenAnswer(inv -> CompletableFuture.completedFuture(new SendResult<>(inv.getArgument(0), metadata)));

        var resultado = new PublicadorKafka(template, Duration.ofSeconds(1)).publicar(42L, sobre());

        var registro = captor.getValue();
        assertEquals("workorders.events", registro.topic());
        assertEquals("42", registro.key());
        var tree = tools.jackson.databind.json.JsonMapper.builder().build().readTree(registro.value());
        assertEquals(7, tree.size());
        assertEquals("ORDEN_CREADA", tree.get("type").asText());
        assertEquals("42", new String(registro.headers().lastHeader("correlationId").value(), StandardCharsets.UTF_8));
        assertEquals(1, resultado.partition());
        assertEquals(7, resultado.offset());
    }

    @Test void brokerConErrorNoEsExito() {
        var template = template();
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("sin brokers")));
        assertEquals("kafka_TimeoutException", assertThrows(PublicadorKafka.PublicacionFallida.class,
                () -> new PublicadorKafka(template, Duration.ofSeconds(1)).publicar(42L, sobre())).getMessage());
    }

    @Test void sinConfirmacionATiempoEsResultadoIncierto() {
        var template = template();
        when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        assertEquals("kafka_timeout_resultado_incierto", assertThrows(PublicadorKafka.PublicacionFallida.class,
                () -> new PublicadorKafka(template, Duration.ofMillis(50)).publicar(42L, sobre())).getMessage());
    }

    @Test void sendQueFallaDeInmediatoNoEsExito() {
        var template = template();
        when(template.send(any(ProducerRecord.class))).thenThrow(new org.springframework.kafka.KafkaException("caido"));
        assertEquals("kafka_KafkaException", assertThrows(PublicadorKafka.PublicacionFallida.class,
                () -> new PublicadorKafka(template, Duration.ofSeconds(1)).publicar(42L, sobre())).getMessage());
    }

    @Test void sinOrdenIdNoPublica() {
        var template = template();
        assertThrows(IllegalArgumentException.class,
                () -> new PublicadorKafka(template, Duration.ofSeconds(1)).publicar(null, sobre()));
        verifyNoInteractions(template);
    }
}
