package cl.digitalfix.workorders;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import cl.digitalfix.workorders.client.CatalogoClient;
import cl.digitalfix.workorders.dto.request.*;
import cl.digitalfix.workorders.entity.EstadoOrden;
import cl.digitalfix.workorders.service.*;
import static org.junit.jupiter.api.Assertions.*;

// Broker real y transacciones H2. Catalog se aísla; esta prueba no certifica stock remoto ni Oracle.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "digitalfix.kafka.enabled=true",
        "digitalfix.kafka.bootstrap-servers=${digitalfix.pruebas.kafka.bootstrap:localhost:9092,localhost:9093,localhost:9094}"})
@EnabledIfSystemProperty(named = "digitalfix.pruebas.kafka", matches = "true")
class KafkaEdicionesRealTests {
    @Autowired OrdenService servicio;
    @Autowired HistorialOrdenService historial;
    @Autowired PlatformTransactionManager transacciones;
    @MockitoBean CatalogoClient catalog;

    @Test void edicionesRealesSePublicanUnaVezYRollbackNoPublica() throws Exception {
        String bootstrap = System.getProperty("digitalfix.pruebas.kafka.bootstrap", "localhost:9092,localhost:9093,localhost:9094");
        var config = new Properties();
        config.put("bootstrap.servers", bootstrap);
        config.put("key.deserializer", StringDeserializer.class.getName());
        config.put("value.deserializer", StringDeserializer.class.getName());
        config.put("enable.auto.commit", "false");
        List<TopicPartition> particiones = new ArrayList<>();
        try (var admin = Admin.create(config)) {
            var topics = admin.describeTopics(List.of("workorders.events", "audit.timeline"))
                    .allTopicNames().get(20, TimeUnit.SECONDS);
            topics.forEach((nombre, info) -> info.partitions().forEach(p -> particiones.add(new TopicPartition(nombre, p.partition()))));
        }
        try (var lector = new KafkaConsumer<String, String>(config)) {
            lector.assign(particiones);
            lector.seekToEnd(particiones);
            // Fija los offsets antes de producir: no mezcla mensajes de ejecuciones anteriores.
            particiones.forEach(p -> lector.position(p, Duration.ofSeconds(10)));
            var actor = new ActorAuditoria("oid-cliente", "Cliente");
            var gestor = new ActorAuditoria("oid-admin", "Admin");
            var orden = servicio.crearOrden(new NuevaOrdenRequest(1L, "Original", "Calle", actor.id(), "cliente@example.org"), actor);
            var datos = new ActualizarOrdenRequest(2L, " Cambio ", " Calle nueva ");
            servicio.actualizarOrden(orden.id(), datos, actor, false, false);
            servicio.actualizarOrden(orden.id(), datos, actor, false, false);
            var contactos = new ContactosRequest("nuevo@example.org", null);
            servicio.actualizarContactos(orden.id(), contactos, actor, false);
            servicio.actualizarContactos(orden.id(), contactos, actor, false);
            servicio.cambiarEstado(orden.id(), new CambioEstadoRequest(EstadoOrden.ASIGNADA, "tecnico", List.of(), "tecnico@example.org"), gestor.id(), true, gestor);
            var repuestos = new ActualizarRepuestosRequest(List.of(), List.of(new RepuestoRequest(4L, 2)));
            servicio.actualizarRepuestos(orden.id(), repuestos, gestor, true);
            servicio.actualizarRepuestos(orden.id(), repuestos, gestor, true);
            new TransactionTemplate(transacciones).executeWithoutResult(tx -> {
                servicio.actualizarContactos(orden.id(), new ContactosRequest("rollback@example.org", null), actor, false);
                tx.setRollbackOnly();
            });
            assertEquals("nuevo@example.org", servicio.consultarOrden(orden.id()).emailContacto());
            assertEquals(5, historial.consultar(orden.id()).size());
            var recibidos = new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
            long limite = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < limite) {
                for (var r : lector.poll(Duration.ofMillis(250))) {
                    if (r.key().equals(orden.id().toString())) recibidos.add(r);
                }
            }
            assertEquals(5, recibidos.size());
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            var porTopico = recibidos.stream().collect(java.util.stream.Collectors.groupingBy(r -> r.topic()));
            assertEquals(2, porTopico.get("workorders.events").size());
            var ediciones = porTopico.get("audit.timeline");
            assertEquals(List.of("ORDEN_DATOS_ACTUALIZADOS", "ORDEN_CONTACTOS_ACTUALIZADOS", "ORDEN_REPUESTOS_ACTUALIZADOS"),
                    ediciones.stream().map(r -> json.readTree(r.value()).get("type").asText()).toList());
            assertEquals(1, ediciones.stream().map(r -> r.partition()).distinct().count());
            for (var r : ediciones) {
                var payload = json.readTree(r.value()).get("payload");
                assertEquals(payload.get("estadoAnterior"), payload.get("estadoNuevo"));
                assertNotNull(payload.get("actorRoles"));
                assertFalse(payload.has("emailContacto"));
            }
        }
    }
}
