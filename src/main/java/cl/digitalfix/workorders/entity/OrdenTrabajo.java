package cl.digitalfix.workorders.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ordenes_trabajo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrdenTrabajo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Referencia lógica al catálogo, sin relación JPA entre microservicios.
    @Column(name = "servicio_id", nullable = false)
    private Long servicioId;

    @ElementCollection
    @CollectionTable(
        name = "orden_repuestos",
        joinColumns = @JoinColumn(name = "orden_id")
    )
    private List<RepuestoOrden> repuestos = new ArrayList<>();

    @Column(nullable = false, length = 1000)
    private String descripcion;

    @Column(nullable = false, length = 300)
    private String direccion;

    @Column(name = "solicitante_id", nullable = false, length = 100)
    private String solicitanteId;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private Instant fechaCreacion;

    @Column(nullable = false, length = 30)
    private String estado;

    @Column(name = "tecnico_id", length = 100)
    private String tecnicoId;

    @Column(name = "actualizado_por", length = 100)
    private String actualizadoPor;

    @Column(name = "fecha_actualizacion")
    private Instant fechaActualizacion;

    public OrdenTrabajo(Long servicioId, String descripcion,
            String direccion, String solicitanteId) {
        this.servicioId = servicioId;
        this.descripcion = descripcion;
        this.direccion = direccion;
        this.solicitanteId = solicitanteId;
    }

    @PrePersist
    void prepararCreacion() {
        // Estos valores se asignan en el servidor antes de guardar.
        fechaCreacion = Instant.now();
        estado = "CREADA";
        registrarCambio(solicitanteId);
    }

    public void actualizar(Long servicioId, String descripcion, String direccion, String actor) {
        this.servicioId = servicioId;
        this.descripcion = descripcion;
        this.direccion = direccion;
        registrarCambio(actor);
    }

    public void actualizarRepuestos(List<RepuestoOrden> repuestos, String actor) {
        this.repuestos.clear();

        if (repuestos != null) {
            this.repuestos.addAll(repuestos);
        }

        registrarCambio(actor);
    }

    public void cambiarEstado(EstadoOrden estado, String tecnicoId, String actor) {
        this.estado = estado.name();
        this.tecnicoId = tecnicoId;
        registrarCambio(actor);
    }

    private void registrarCambio(String actor) {
        actualizadoPor = actor;
        fechaActualizacion = Instant.now();
    }
}
