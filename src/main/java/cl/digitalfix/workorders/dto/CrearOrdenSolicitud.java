package cl.digitalfix.workorders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// El cliente no decide el identificador, la fecha ni el estado de la orden.
public record CrearOrdenSolicitud(
    @NotNull(message = "El servicio es obligatorio")
    @Positive(message = "El identificador del servicio debe ser positivo")
    Long servicioId,

    @NotBlank(message = "La descripción es obligatoria")
    @Size(max = 1000, message = "La descripción admite hasta 1000 caracteres")
    String descripcion,

    @NotBlank(message = "La dirección es obligatoria")
    @Size(max = 300, message = "La dirección admite hasta 300 caracteres")
    String direccion,

    @NotBlank(message = "El solicitante es obligatorio")
    @Size(max = 100, message = "El solicitante admite hasta 100 caracteres")
    String solicitanteId
) {
}