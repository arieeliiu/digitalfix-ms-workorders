package cl.digitalfix.workorders.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.digitalfix.workorders.entity.HistorialOrden;

public interface HistorialOrdenRepository extends JpaRepository<HistorialOrden, Long> {

    List<HistorialOrden> findByOrdenIdOrderByFechaAscIdAsc(Long ordenId);

    boolean existsByOrdenId(Long ordenId);
}
