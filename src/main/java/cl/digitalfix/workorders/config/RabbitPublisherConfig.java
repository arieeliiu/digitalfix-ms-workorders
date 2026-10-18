package cl.digitalfix.workorders.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitPublisherConfig {
    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory conexion) {
        var template = new RabbitTemplate(conexion);
        template.setMandatory(true);
        return template;
    }
}
