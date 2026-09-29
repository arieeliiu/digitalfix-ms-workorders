package cl.digitalfix.workorders.client;

import java.util.List;

import cl.digitalfix.workorders.dto.RepuestoOrdenSolicitud;

public record DescontarStockCatalogSolicitud(
        Long ordenId,
        List<RepuestoOrdenSolicitud> repuestos
) {
}