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
    }

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