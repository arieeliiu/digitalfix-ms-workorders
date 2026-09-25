package cl.digitalfix.workorders.dto;

import java.util.List;

import cl.digitalfix.workorders.entity.EstadoOrden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CambiarEstadoSolicitud(

    @NotNull
    EstadoOrden status,

    @Size(max = 100)
    String tecnicoId,

    List<@Valid RepuestoOrdenSolicitud> repuestos

) {
}