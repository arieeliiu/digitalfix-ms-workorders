package cl.digitalfix.workorders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import cl.digitalfix.workorders.client.CatalogoClient;
import cl.digitalfix.workorders.dto.request.*;
import cl.digitalfix.workorders.entity.EstadoOrden;
import cl.digitalfix.workorders.messaging.PublicadorRabbit;
import cl.digitalfix.workorders.messaging.ContratosMensajeria.*;
import cl.digitalfix.workorders.service.OrdenService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={
        "digitalfix.rabbit.enabled=true", "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true", "spring.rabbitmq.dynamic=false",
        "spring.rabbitmq.connection-timeout=3s"})
@EnabledIfEnvironmentVariable(named="RABBITMQ_TEST_VHOST",matches="workorders-it-.+")
class RabbitRealTests {
    @Autowired PublicadorRabbit publicador;
    @Autowired OrdenService ordenes;
    @MockitoBean CatalogoClient catalog;
    @DynamicPropertySource static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.addresses", () -> "rabbitmq1:5672,rabbitmq2:5672");
        r.add("spring.rabbitmq.virtual-host", () -> System.getenv("RABBITMQ_TEST_VHOST"));
        r.add("spring.rabbitmq.username", () -> System.getenv("RABBITMQ_TEST_USERNAME"));
        r.add("spring.rabbitmq.password", () -> System.getenv("RABBITMQ_TEST_PASSWORD"));
    }

    @Test void transicionesPublicanCincoMensajesConfirmadosSinDuplicar() throws Exception {
        when(catalog.nombreServicio(2L)).thenReturn("Revisión eléctrica");
        var orden=ordenes.crearOrden(new NuevaOrdenRequest(2L,"Prueba aislada","Ñuñoa","oid-prueba","cliente@example.invalid"));
        var asignar=new CambioEstadoRequest(EstadoOrden.ASIGNADA,"tecnico",List.of(new RepuestoRequest(4L,2)),"tecnico@example.invalid");
        ordenes.cambiarEstado(orden.id(),asignar,"operador",true);
        ordenes.cambiarEstado(orden.id(),asignar,"operador",true);
        for (var estado : List.of(EstadoOrden.EN_DESPLAZAMIENTO,EstadoOrden.EN_EJECUCION,EstadoOrden.CERRADA,EstadoOrden.CERRADA))
            ordenes.cambiarEstado(orden.id(),new CambioEstadoRequest(estado,null,null),"operador",true);
        // Observador exclusivo de pruebas, distinto del usuario productor (sin Read/Configure).
        var cf=new com.rabbitmq.client.ConnectionFactory();
        cf.setHost("rabbitmq1"); cf.setVirtualHost(System.getenv("RABBITMQ_TEST_VHOST"));
        cf.setUsername(System.getenv("RABBITMQ_OBSERVER_USERNAME")); cf.setPassword(System.getenv("RABBITMQ_OBSERVER_PASSWORD"));
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var tipos=new java.util.ArrayList<String>(); var ids=new java.util.HashSet<String>(); var traces=new java.util.ArrayList<String>();
        try (var conexion=cf.newConnection(); var canal=conexion.createChannel()) {
            for (int i=0; i<5; i++) {
                var entrega=canal.basicGet("q.workorders.it",false);
                assertNotNull(entrega,"Falta comando confirmado");
                assertEquals(2,entrega.getProps().getDeliveryMode());
                assertEquals("application/json",entrega.getProps().getContentType());
                var s=json.readTree(entrega.getBody());
                assertEquals(7,s.size()); assertEquals("1.0",s.get("schemaVersion").asText());
                assertEquals(orden.id().toString(),s.get("correlationId").asText());
                assertEquals(s.get("eventId").asText(),entrega.getProps().getMessageId());
                tipos.add(s.get("type").asText()); ids.add(s.get("eventId").asText()); traces.add(s.get("traceId").asText());
                if (i==2) assertEquals("cmd.topic",entrega.getEnvelope().getExchange());
                if (i==4) { assertEquals("CERRADA",s.at("/payload/estado").asText()); assertEquals(2,s.at("/payload/repuestos/0/cantidad").asInt()); }
                canal.basicAck(entrega.getEnvelope().getDeliveryTag(),false);
            }
            assertNull(canal.basicGet("q.workorders.it",true));
        }
        assertEquals(List.of("ORDEN_ASIGNADA","DESPACHO_TECNICO","TECNICO_EN_CAMINO","ORDEN_CERRADA","GENERAR_REPORTE"),tipos);
        assertEquals(5,ids.size()); assertEquals(traces.get(0),traces.get(1)); assertEquals(traces.get(3),traces.get(4));
        verify(catalog,times(1)).descontarStock(any(),any());
    }

    @Test void mandatoryDetectaRetornoRealSinRuta() {
        var comando=new Comando("cmd.direct","sin.ruta."+UUID.randomUUID(),new Sobre("1.0","ORDEN_ASIGNADA",UUID.randomUUID(),
                Instant.now(),UUID.randomUUID(),"999",new Correo(999L,"prueba@example.invalid")),null);
        assertEquals("mensaje_retornado_sin_ruta",assertThrows(PublicadorRabbit.PublicacionFallida.class,
                () -> publicador.publicar(comando)).getMessage());
    }
}
