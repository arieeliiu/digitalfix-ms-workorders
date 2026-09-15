package cl.digitalfix.workorders.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import cl.digitalfix.workorders.entity.OrdenTrabajo;

public interface OrdenTrabajoRepositorio
        extends JpaRepository<OrdenTrabajo, Long> {
    java.util.List<OrdenTrabajo> findBySolicitanteId(String solicitanteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrdenTrabajo o where o.id = :id")
    Optional<OrdenTrabajo> findByIdParaModificar(@Param("id") Long id);
}
