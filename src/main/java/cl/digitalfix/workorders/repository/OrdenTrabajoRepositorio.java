package cl.digitalfix.workorders.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.digitalfix.workorders.entity.OrdenTrabajo;

public interface OrdenTrabajoRepositorio
        extends JpaRepository<OrdenTrabajo, Long> {
    java.util.List<OrdenTrabajo> findBySolicitanteId(String solicitanteId);
}
