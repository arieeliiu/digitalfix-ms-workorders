package cl.digitalfix.workorders.mapper;

import cl.digitalfix.workorders.entity.OrdenTrabajo;
import cl.digitalfix.workorders.dto.response.OrdenTrabajoResponse;
import cl.digitalfix.workorders.dto.response.RepuestoOrdenResponse;

public final class OrdenTrabajoMapper {
    private OrdenTrabajoMapper() {}

    public static OrdenTrabajoResponse respuesta(OrdenTrabajo orden) {
        return new OrdenTrabajoResponse(orden.getId(), orden.getServicioId(),
                orden.getDescripcion(), orden.getDireccion(), orden.getSolicitanteId(),
                orden.getFechaCreacion(), orden.getEstado(), orden.getTecnicoId(),
                orden.getActualizadoPor(), orden.getFechaActualizacion(),
                orden.getRepuestos().stream().map(repuesto -> new RepuestoOrdenResponse(
                        repuesto.getRepuestoId(), repuesto.getCantidad())).toList());
    }
}
