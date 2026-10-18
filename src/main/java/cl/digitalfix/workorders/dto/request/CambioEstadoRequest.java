package cl.digitalfix.workorders.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import cl.digitalfix.workorders.entity.EstadoOrden;

public record CambioEstadoRequest(

    @NotNull
    EstadoOrden status,

    @Size(max = 100)
    String tecnicoId,

    List<@Valid RepuestoRequest> repuestos,
    @Email @Size(max = 254)
    String emailTecnico

) {
    public CambioEstadoRequest(EstadoOrden status, String tecnicoId, List<RepuestoRequest> repuestos) {
        this(status, tecnicoId, repuestos, null);
    }
}
