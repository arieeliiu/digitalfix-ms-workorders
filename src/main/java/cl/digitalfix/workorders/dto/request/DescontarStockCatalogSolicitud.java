package cl.digitalfix.workorders.dto.request;

import java.util.List;

import cl.digitalfix.workorders.dto.request.RepuestoOrdenSolicitud;

public record DescontarStockCatalogSolicitud(
        Long ordenId,
        List<RepuestoOrdenSolicitud> repuestos
) {
}
