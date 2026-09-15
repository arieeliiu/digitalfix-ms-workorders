package cl.digitalfix.workorders.entity;

public enum EstadoOrden {
    CREADA, ASIGNADA, EN_DESPLAZAMIENTO, EN_EJECUCION, CERRADA, CANCELADA;

    public boolean permite(EstadoOrden siguiente) {
        if (this == siguiente) return true;
        if (this == CERRADA || this == CANCELADA) return false;
        if (siguiente == CANCELADA) return true;
        return switch (this) {
            case CREADA -> siguiente == ASIGNADA;
            case ASIGNADA -> siguiente == EN_DESPLAZAMIENTO;
            case EN_DESPLAZAMIENTO -> siguiente == EN_EJECUCION;
            case EN_EJECUCION -> siguiente == CERRADA;
            default -> false;
        };
    }
}
