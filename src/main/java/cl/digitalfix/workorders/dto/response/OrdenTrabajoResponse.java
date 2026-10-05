package cl.digitalfix.workorders.dto.response;

import java.time.Instant;
import java.util.List;

public record OrdenTrabajoResponse(
        Long id,
        Long servicioId,
        String descripcion,
        String direccion,
        String solicitanteId,
        Instant fechaCreacion,
        String estado,
        String tecnicoId,
        String actualizadoPor,
        Instant fechaActualizacion,
        List<RepuestoOrdenResponse> repuestos
) {}
