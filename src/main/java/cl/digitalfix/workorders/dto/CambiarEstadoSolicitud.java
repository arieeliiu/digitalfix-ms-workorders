package cl.digitalfix.workorders.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import cl.digitalfix.workorders.entity.EstadoOrden;

public record CambiarEstadoSolicitud(
    @NotNull EstadoOrden status,
    @Size(max = 100) String tecnicoId
) {}
