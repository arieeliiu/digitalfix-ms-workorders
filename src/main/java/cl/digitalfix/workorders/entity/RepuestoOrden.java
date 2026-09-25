package cl.digitalfix.workorders.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class RepuestoOrden {

    @Column(name = "repuesto_id", nullable = false)
    private Long repuestoId;

    @Column(nullable = false)
    private Integer cantidad;

    protected RepuestoOrden() {
        // Constructor requerido por JPA.
    }

    public RepuestoOrden(Long repuestoId, Integer cantidad) {
        this.repuestoId = repuestoId;
        this.cantidad = cantidad;
    }

    public Long getRepuestoId() {
        return repuestoId;
    }

    public Integer getCantidad() {
        return cantidad;
    }
}