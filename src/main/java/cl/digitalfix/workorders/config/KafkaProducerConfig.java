package cl.digitalfix.workorders.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaProducerConfig {

    @Bean
    ProducerFactory<String, String> kafkaProducerFactory(
            @Value("${digitalfix.kafka.bootstrap-servers}") String servidores,
            @Value("${digitalfix.kafka.timeout:5s}") Duration timeout) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        // Key = workorderId y value = JSON del Sobre, ambos como texto
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Confirman el leader y todos los ISR
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // Si Kafka esta caido, send() esperaría 60 s por defecto y dejaría colgada la respuesta HTTP.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) timeout.toMillis());
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> kafkaProducerFactory) {
        return new KafkaTemplate<>(kafkaProducerFactory);
    }
}
