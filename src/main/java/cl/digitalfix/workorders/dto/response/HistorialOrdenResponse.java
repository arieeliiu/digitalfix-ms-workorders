package cl.digitalfix.workorders.dto.response;

import java.time.Instant;

public record HistorialOrdenResponse(
        Long id,
        Long ordenId,
        String accion,
        String estadoAnterior,
        String estadoNuevo,
        String tecnicoId,
        String actorId,
        String actorRoles,
        Instant fecha
) {}
