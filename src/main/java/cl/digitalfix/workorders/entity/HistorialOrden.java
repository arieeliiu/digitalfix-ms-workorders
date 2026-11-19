package cl.digitalfix.workorders.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Registro inmutable de auditoría. Sin relación JPA con OrdenTrabajo:
// el historial debe sobrevivir a la eliminación de la orden.
@Entity
@Table(name = "historial_ordenes",
        indexes = @Index(name = "ix_historial_ordenes_orden", columnList = "orden_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HistorialOrden {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "orden_id", nullable = false, updatable = false)
    private Long ordenId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private AccionHistorial accion;

    @Column(name = "estado_anterior", updatable = false, length = 30)
    private String estadoAnterior;

    @Column(name = "estado_nuevo", updatable = false, length = 30)
    private String estadoNuevo;

    @Column(name = "tecnico_id", updatable = false, length = 100)
    private String tecnicoId;

    // oid de Entra de quien ejecutó la acción.
    @Column(name = "actor_id", nullable = false, updatable = false, length = 100)
    private String actorId;

    // Roles de la aplicación que tenía el actor (ej. "Admin,Operador").
    @Column(name = "actor_roles", updatable = false, length = 100)
    private String actorRoles;

    @Column(nullable = false, updatable = false)
    private Instant fecha;

    public HistorialOrden(Long ordenId, AccionHistorial accion, String estadoAnterior,
            String estadoNuevo, String tecnicoId, String actorId, String actorRoles) {
        this.ordenId = ordenId;
        this.accion = accion;
        this.estadoAnterior = estadoAnterior;
        this.estadoNuevo = estadoNuevo;
        this.tecnicoId = tecnicoId;
        this.actorId = actorId;
        this.actorRoles = actorRoles;
        this.fecha = Instant.now();
    }
}
