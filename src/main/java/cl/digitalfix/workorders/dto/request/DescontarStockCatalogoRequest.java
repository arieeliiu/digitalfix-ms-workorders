package cl.digitalfix.workorders.dto.request;

import java.util.List;

public record DescontarStockCatalogoRequest(
        Long ordenId,
        List<RepuestoRequest> repuestos
) {
}
