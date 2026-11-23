package cl.digitalfix.workorders.messaging.kafka;

import java.time.Instant;
import cl.digitalfix.workorders.entity.HistorialOrden;

// Payload de los eventos de workorders.events: lo mismo que guarda historial_ordenes.
// Quién (actorId, actorRoles), qué (accion, estados, tecnicoId) y cuándo (fecha).
// Sin correos ni descripciones, igual que el historial.
public record EventoOrden(Long ordenId, String accion, String estadoAnterior, String estadoNuevo,
                          String tecnicoId, String actorId, String actorRoles, Instant fecha) {

    public static EventoOrden desde(HistorialOrden h) {
        return new EventoOrden(h.getOrdenId(), h.getAccion().name(), h.getEstadoAnterior(),
                h.getEstadoNuevo(), h.getTecnicoId(), h.getActorId(), h.getActorRoles(), h.getFecha());
    }

    // Tipo del Sobre: un hecho en pasado.
    public String tipo() {
        return switch (accion) {
            case "CREACION" -> "ORDEN_CREADA";
            case "CAMBIO_ESTADO" -> "ORDEN_ESTADO_CAMBIADO";
            case "ELIMINACION" -> "ORDEN_ELIMINADA";
            case "EDICION_DATOS" -> "ORDEN_DATOS_ACTUALIZADOS";
            case "EDICION_CONTACTOS" -> "ORDEN_CONTACTOS_ACTUALIZADOS";
            case "EDICION_REPUESTOS" -> "ORDEN_REPUESTOS_ACTUALIZADOS";
            default -> throw new IllegalArgumentException("Acción sin evento: " + accion);
        };
    }
}
