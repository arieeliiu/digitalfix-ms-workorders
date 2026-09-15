package cl.digitalfix.workorders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ActualizarOrdenSolicitud(
    @NotNull @Positive Long servicioId,
    @NotBlank @Size(max = 1000) String descripcion,
    @NotBlank @Size(max = 300) String direccion
) {}
