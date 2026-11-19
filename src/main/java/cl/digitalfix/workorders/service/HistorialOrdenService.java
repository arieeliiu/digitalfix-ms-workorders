package cl.digitalfix.workorders.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import cl.digitalfix.workorders.dto.response.HistorialOrdenResponse;
import cl.digitalfix.workorders.entity.AccionHistorial;
import cl.digitalfix.workorders.entity.HistorialOrden;
import cl.digitalfix.workorders.repository.HistorialOrdenRepository;

// Trazabilidad de las órdenes para auditoría.
// Los registros se escriben en la misma transacción que el cambio: si la orden
// se confirma, su historial también; si hay rollback, no queda ninguno de los dos.
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HistorialOrdenService {

    private final HistorialOrdenRepository repositorio;

    // MANDATORY: registrar fuera de la transacción del cambio rompería la garantía anterior.
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarCreacion(Long ordenId, String actorId, String actorRoles) {
        repositorio.save(new HistorialOrden(ordenId, AccionHistorial.CREACION,
                null, "CREADA", null, actorId, actorRoles));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarCambioEstado(Long ordenId, String anterior, String nuevo,
            String tecnicoId, String actorId, String actorRoles) {
        repositorio.save(new HistorialOrden(ordenId, AccionHistorial.CAMBIO_ESTADO,
                anterior, nuevo, tecnicoId, actorId, actorRoles));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarEliminacion(Long ordenId, String estadoFinal, String tecnicoId,
            String actorId, String actorRoles) {
        repositorio.save(new HistorialOrden(ordenId, AccionHistorial.ELIMINACION,
                estadoFinal, null, tecnicoId, actorId, actorRoles));
    }

    public boolean tieneRegistros(Long ordenId) {
        return repositorio.existsByOrdenId(ordenId);
    }

    public List<HistorialOrdenResponse> consultar(Long ordenId) {
        return repositorio.findByOrdenIdOrderByFechaAscIdAsc(ordenId).stream()
                .map(h -> new HistorialOrdenResponse(h.getId(), h.getOrdenId(),
                        h.getAccion().name(), h.getEstadoAnterior(), h.getEstadoNuevo(),
                        h.getTecnicoId(), h.getActorId(), h.getActorRoles(), h.getFecha()))
                .toList();
    }
}
