package cl.digitalfix.workorders.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import cl.digitalfix.workorders.dto.response.HistorialOrdenResponse;
import cl.digitalfix.workorders.entity.AccionHistorial;
import cl.digitalfix.workorders.entity.HistorialOrden;
import cl.digitalfix.workorders.entity.OrdenTrabajo;
import cl.digitalfix.workorders.messaging.kafka.CoordinadorEventosOrden;
import cl.digitalfix.workorders.messaging.kafka.EventoOrden;
import cl.digitalfix.workorders.repository.HistorialOrdenRepository;

// Trazabilidad de las órdenes para auditoría.
// Los registros se escriben en la misma transacción que el cambio: si la orden
// se confirma, su historial también; si hay rollback, no queda ninguno de los dos.
// Cada registro produce un evento en su tópico Kafka, solo después del commit.
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HistorialOrdenService {

    private final HistorialOrdenRepository repositorio;
    private final CoordinadorEventosOrden eventos;

    // MANDATORY: registrar fuera de la transacción del cambio rompería la garantía anterior.
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarCreacion(Long ordenId, String actorId, String actorRoles) {
        registrar(new HistorialOrden(ordenId, AccionHistorial.CREACION,
                null, "CREADA", null, actorId, actorRoles));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarCambioEstado(Long ordenId, String anterior, String nuevo,
            String tecnicoId, String actorId, String actorRoles) {
        registrar(new HistorialOrden(ordenId, AccionHistorial.CAMBIO_ESTADO,
                anterior, nuevo, tecnicoId, actorId, actorRoles));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarEliminacion(Long ordenId, String estadoFinal, String tecnicoId,
            String actorId, String actorRoles) {
        registrar(new HistorialOrden(ordenId, AccionHistorial.ELIMINACION,
                estadoFinal, null, tecnicoId, actorId, actorRoles));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarEdicion(OrdenTrabajo orden, AccionHistorial accion, ActorAuditoria actor) {
        if (!List.of(AccionHistorial.EDICION_DATOS, AccionHistorial.EDICION_CONTACTOS,
                AccionHistorial.EDICION_REPUESTOS).contains(accion))
            throw new IllegalArgumentException("La acción debe representar una edición");
        // La edición no es una transición: ambos estados describen el estado actual.
        registrar(new HistorialOrden(orden.getId(), accion, orden.getEstado(), orden.getEstado(),
                orden.getTecnicoId(), actor.id(), actor.roles()));
    }

    // Historial y evento salen del mismo registro: lo que queda en la tabla es lo que se publica.
    private void registrar(HistorialOrden registro) {
        var guardado = repositorio.save(registro);
        eventos.despuesDelCommit(EventoOrden.desde(guardado));
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
