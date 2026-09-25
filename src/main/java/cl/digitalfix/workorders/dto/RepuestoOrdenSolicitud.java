package cl.digitalfix.workorders.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RepuestoOrdenSolicitud(

    @NotNull(message = "El repuesto es obligatorio")
    @Positive(message = "El identificador del repuesto debe ser positivo")
    Long repuestoId,

    @NotNull(message = "La cantidad es obligatoria")
    @Positive(message = "La cantidad debe ser mayor que cero")
    Integer cantidad

) {
}