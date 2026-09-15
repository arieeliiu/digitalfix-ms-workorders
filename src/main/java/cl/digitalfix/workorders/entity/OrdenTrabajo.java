package cl.digitalfix.workorders.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "ordenes_trabajo")
public class OrdenTrabajo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Referencia lógica al catálogo, sin relación JPA entre microservicios.
    @Column(name = "servicio_id", nullable = false)
    private Long servicioId;

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

    protected OrdenTrabajo() {
        // Constructor requerido por JPA.
    }

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

    public void cambiarEstado(EstadoOrden estado, String tecnicoId, String actor) {
        this.estado = estado.name();
        this.tecnicoId = tecnicoId;
        registrarCambio(actor);
    }

    private void registrarCambio(String actor) {
        actualizadoPor = actor;
        fechaActualizacion = Instant.now();
    }

    public String getTecnicoId() { return tecnicoId; }
    public String getActualizadoPor() { return actualizadoPor; }
    public Instant getFechaActualizacion() { return fechaActualizacion; }

    public Long getId() {
        return id;
    }

    public Long getServicioId() {
        return servicioId;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public String getDireccion() {
        return direccion;
    }

    public String getSolicitanteId() {
        return solicitanteId;
    }

    public Instant getFechaCreacion() {
        return fechaCreacion;
    }

    public String getEstado() {
        return estado;
    }
}
