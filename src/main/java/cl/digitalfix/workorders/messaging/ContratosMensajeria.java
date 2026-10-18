package cl.digitalfix.workorders.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class ContratosMensajeria {
    private ContratosMensajeria() {}
    public record Sobre(String schemaVersion, String type, UUID eventId, Instant timestamp,
                        UUID traceId, String correlationId, Object payload) {}
    public record Comando(String exchange, String routingKey, Sobre sobre, String omision) {}
    public record Correo(@NotNull @Positive Long ordenId, @NotBlank @Email @Size(max=254) String emailContacto) {}
    public record Despacho(@NotNull @Positive Long ordenId, @NotBlank @Email @Size(max=254) String emailTecnico,
                           @NotBlank @Size(max=100) String tecnicoId, @NotNull @Positive Long servicioId,
                           @NotBlank @Size(max=200) String servicioNombre, @NotBlank @Size(max=300) String direccion) {}
    public record Repuesto(@NotNull @Positive Long repuestoId, @NotNull @Positive Integer cantidad) {}
    public record Informe(@NotNull @Positive Long ordenId, @NotNull @Pattern(regexp="CERRADA") String estado,
                          @NotBlank @Size(max=100) String solicitanteId, @NotBlank @Size(max=100) String tecnicoId,
                          @NotNull @Positive Long servicioId, @NotBlank @Size(max=1000) String descripcion,
                          @NotBlank @Size(max=300) String direccion, @NotNull Instant fechaCreacion,
                          @NotNull Instant fechaCierre, @NotNull @Size(max=1000) List<@Valid @NotNull Repuesto> repuestos) {}
}
