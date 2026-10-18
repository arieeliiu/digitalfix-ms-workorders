package cl.digitalfix.workorders.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import cl.digitalfix.workorders.client.CatalogoClient;
import cl.digitalfix.workorders.dto.response.OrdenResponse;
import static cl.digitalfix.workorders.messaging.ContratosMensajeria.*;

@Component
public class NotificacionesOrden {
    private static final Logger log = LoggerFactory.getLogger(NotificacionesOrden.class);
    private final PublicadorRabbit publicador;
    private final CatalogoClient catalog;
    private final Validator validator;
    private final boolean habilitado;

    public NotificacionesOrden(PublicadorRabbit publicador, CatalogoClient catalog, Validator validator,
            @Value("${digitalfix.rabbit.enabled:false}") boolean habilitado) {
        this.publicador = publicador; this.catalog = catalog; this.validator = validator; this.habilitado = habilitado;
        log.info("mensajeria_workorders habilitada={}", habilitado);
    }

    public void despuesDelCommit(OrdenResponse orden) {
        if (!habilitado || !List.of("ASIGNADA","EN_DESPLAZAMIENTO","CERRADA").contains(orden.estado())) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Publicación exige una transacción activa");
        // Snapshot de valores/records, nunca entidades o colecciones lazy tras el commit.
        List<Comando> comandos = preparar(orden);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for (Comando c : comandos) {
                    if (c.omision() != null) { registrar(c, "omitido", c.omision()); continue; }
                    try { publicador.publicar(c); registrar(c, "publicado", "confirm_positivo_sin_retorno"); }
                    catch (Exception e) {
                        // Nunca propagar fallos secundarios como error HTTP de una orden ya confirmada.
                        registrar(c, "fallido", e instanceof PublicadorRabbit.PublicacionFallida
                                ? e.getMessage() : e.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    private List<Comando> preparar(OrdenResponse o) {
        UUID trace = UUID.randomUUID(); Instant fecha = Instant.now();
        var comandos = new ArrayList<Comando>();
        String tipo = switch (o.estado()) {
            case "ASIGNADA" -> "ORDEN_ASIGNADA";
            case "EN_DESPLAZAMIENTO" -> "TECNICO_EN_CAMINO";
            default -> "ORDEN_CERRADA";
        };
        boolean topic = o.estado().equals("EN_DESPLAZAMIENTO");
        comandos.add(comando(o, trace, fecha, tipo, topic ? "cmd.topic" : "cmd.direct",
                topic ? "email.en_camino" : "email.send", new Correo(o.id(),o.emailContacto()), "contacto_ausente_o_invalido"));
        if (o.estado().equals("ASIGNADA")) {
            String nombre = null;
            // No consultar Catalog si ya falta el destinatario del ticket.
            if (validator.validate(new Correo(o.id(),o.emailTecnico())).isEmpty()) {
                try { nombre = catalog.nombreServicio(o.servicioId()); }
                catch (Exception e) { /* El comando omitido conserva identificadores de trazabilidad. */ }
            }
            comandos.add(comando(o, trace, fecha, "DESPACHO_TECNICO", "cmd.direct", "dispatch.ticket",
                    new Despacho(o.id(),o.emailTecnico(),o.tecnicoId(),o.servicioId(),nombre,o.direccion()),
                    nombre == null ? "contacto_o_nombre_servicio_no_disponible" : "datos_despacho_invalidos"));
        }
        if (o.estado().equals("CERRADA")) {
            var informe = new Informe(o.id(),o.estado(),o.solicitanteId(),o.tecnicoId(),o.servicioId(),o.descripcion(),
                    o.direccion(),o.fechaCreacion(),o.fechaActualizacion(),o.repuestos().stream()
                    .map(r -> new Repuesto(r.repuestoId(),r.cantidad())).toList());
            var c = comando(o,trace,fecha,"GENERAR_REPORTE","cmd.direct","report.gen",informe,"datos_informe_invalidos");
            if (c.omision() == null && informe.fechaCierre().isBefore(informe.fechaCreacion()))
                c = new Comando(c.exchange(),c.routingKey(),c.sobre(),"fechas_informe_invalidas");
            comandos.add(c);
        }
        return List.copyOf(comandos);
    }

    private Comando comando(OrdenResponse o, UUID trace, Instant fecha, String tipo, String exchange,
                             String key, Object payload, String razon) {
        return new Comando(exchange,key,new Sobre("1.0",tipo,UUID.randomUUID(),fecha,trace,o.id().toString(),payload),
                validator.validate(payload).isEmpty() ? null : razon);
    }
    private void registrar(Comando c, String resultado, String motivo) {
        var s = c.sobre();
        log.info("comando resultado={} tipo={} eventId={} traceId={} ordenId={} motivo={}",
                resultado,s.type(),s.eventId(),s.traceId(),s.correlationId(),motivo);
    }
}
