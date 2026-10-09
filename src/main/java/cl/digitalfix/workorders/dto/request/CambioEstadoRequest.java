package cl.digitalfix.workorders.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import cl.digitalfix.workorders.entity.EstadoOrden;

public record CambioEstadoRequest(

    @NotNull
    EstadoOrden status,

    @Size(max = 100)
    String tecnicoId,

    List<@Valid RepuestoRequest> repuestos

) {
}
