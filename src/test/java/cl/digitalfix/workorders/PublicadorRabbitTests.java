package cl.digitalfix.workorders;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import cl.digitalfix.workorders.messaging.PublicadorRabbit;
import cl.digitalfix.workorders.messaging.ContratosMensajeria.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicadorRabbitTests {
    Comando comando() {
        return new Comando("cmd.direct","email.send",new Sobre("1.0","ORDEN_ASIGNADA",UUID.randomUUID(),
                Instant.now(),UUID.randomUUID(),"15",new Correo(15L,"prueba@example.org")),null);
    }
    @Test void confirmPositivoMensajePersistenteYJson() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        Comando comando = comando();
        doAnswer(inv -> {
            Message m=inv.getArgument(2); CorrelationData c=inv.getArgument(3);
            assertEquals(MessageDeliveryMode.PERSISTENT,m.getMessageProperties().getDeliveryMode());
            assertEquals("application/json",m.getMessageProperties().getContentType());
            assertEquals(comando.sobre().eventId().toString(),m.getMessageProperties().getMessageId());
            var tree=tools.jackson.databind.json.JsonMapper.builder().build().readTree(m.getBody());
            assertEquals(7,tree.size()); assertEquals(2,tree.get("payload").size());
            assertEquals("prueba@example.org",tree.at("/payload/emailContacto").asText());
            c.getFuture().complete(new CorrelationData.Confirm(true,null)); return null;
        }).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        new PublicadorRabbit(template,Duration.ofSeconds(1)).publicar(comando);
    }
    @Test void retornoAunqueConfirmAckNoEsExito() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(inv -> {
            CorrelationData c=inv.getArgument(3);
            c.setReturned(new ReturnedMessage(inv.getArgument(2),312,"NO_ROUTE","cmd.direct","sin.ruta"));
            c.getFuture().complete(new CorrelationData.Confirm(true,null)); return null;
        }).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertEquals("mensaje_retornado_sin_ruta",assertThrows(PublicadorRabbit.PublicacionFallida.class,
                () -> new PublicadorRabbit(template,Duration.ofSeconds(1)).publicar(comando())).getMessage());
    }
    @Test void nackYTimeoutNoSonExito() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(inv -> { ((CorrelationData)inv.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(false,"no")); return null; })
                .when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertEquals("broker_nack",assertThrows(PublicadorRabbit.PublicacionFallida.class,
                () -> new PublicadorRabbit(template,Duration.ofMillis(50)).publicar(comando())).getMessage());
        reset(template);
        assertEquals("confirm_timeout_resultado_incierto",assertThrows(PublicadorRabbit.PublicacionFallida.class,
                () -> new PublicadorRabbit(template,Duration.ofMillis(50)).publicar(comando())).getMessage());
    }
}
