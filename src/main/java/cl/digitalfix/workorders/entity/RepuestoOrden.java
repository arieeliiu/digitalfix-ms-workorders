package cl.digitalfix.workorders.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RepuestoOrden {

    @Column(name = "repuesto_id", nullable = false)
    private Long repuestoId;

    @Column(nullable = false)
    private Integer cantidad;
}
