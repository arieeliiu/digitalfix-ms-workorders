package cl.digitalfix.workorders.mapper;

import cl.digitalfix.workorders.dto.response.OrdenResponse;
import cl.digitalfix.workorders.dto.response.RepuestoOrdenResponse;
import cl.digitalfix.workorders.entity.OrdenTrabajo;

public final class OrdenMapper {
    private OrdenMapper() {}

    public static OrdenResponse respuesta(OrdenTrabajo orden) {
        return new OrdenResponse(orden.getId(), orden.getServicioId(),
                orden.getDescripcion(), orden.getDireccion(), orden.getSolicitanteId(),
                orden.getFechaCreacion(), orden.getEstado(), orden.getTecnicoId(),
                orden.getActualizadoPor(), orden.getFechaActualizacion(),
                orden.getRepuestos().stream().map(repuesto -> new RepuestoOrdenResponse(
                        repuesto.getRepuestoId(), repuesto.getCantidad())).toList());
    }
}
