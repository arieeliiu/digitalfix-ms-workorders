package cl.digitalfix.workorders.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import cl.digitalfix.workorders.dto.CrearOrdenSolicitud;
import cl.digitalfix.workorders.dto.ActualizarOrdenSolicitud;
import cl.digitalfix.workorders.dto.CambiarEstadoSolicitud;
import cl.digitalfix.workorders.entity.EstadoOrden;
import cl.digitalfix.workorders.entity.OrdenTrabajo;
import cl.digitalfix.workorders.repository.OrdenTrabajoRepositorio;
import cl.digitalfix.workorders.entity.RepuestoOrden;

@Service
@Transactional(readOnly = true)
public class OrdenTrabajoServicio {

    private final OrdenTrabajoRepositorio repositorio;

    public OrdenTrabajoServicio(OrdenTrabajoRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    @Transactional
    public OrdenTrabajo crearOrden(CrearOrdenSolicitud solicitud) {
        var orden = new OrdenTrabajo(
            solicitud.servicioId(),
            solicitud.descripcion().trim(),
            solicitud.direccion().trim(),
            solicitud.solicitanteId().trim()
        );

        return repositorio.save(orden);
    }

    public OrdenTrabajo consultarOrden(Long id) {
        return repositorio.findById(id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No existe una orden con id " + id
            ));
    }

    public List<OrdenTrabajo> listarOrdenes() {
        return repositorio.findAll();
    }

    public List<OrdenTrabajo> listarOrdenesDelSolicitante(String solicitanteId) {
        return repositorio.findBySolicitanteId(solicitanteId);
    }

    @Transactional
    public OrdenTrabajo actualizarOrden(Long id, ActualizarOrdenSolicitud datos, String solicitanteId) {
        var orden = ordenPropiaParaModificar(id, solicitanteId);
        if (!"CREADA".equals(orden.getEstado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo se pueden editar órdenes creadas");
        }
        orden.actualizar(datos.servicioId(), datos.descripcion().trim(), datos.direccion().trim(), solicitanteId);
        return repositorio.save(orden);
    }

    @Transactional
    public OrdenTrabajo cambiarEstado(Long id, CambiarEstadoSolicitud datos, String solicitanteId) {
        var orden = ordenPropiaParaModificar(id, solicitanteId);
        var actual = EstadoOrden.valueOf(orden.getEstado());
        var siguiente = datos.status();
        if (!actual.permite(siguiente)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transición de estado no permitida");
        }
        String tecnico = orden.getTecnicoId();

        if (siguiente == EstadoOrden.ASIGNADA) {

            if (datos.tecnicoId() != null) {
                tecnico = datos.tecnicoId().trim();
            }

            var repuestos = datos.repuestos() == null
                    ? List.<RepuestoOrden>of()
                    : datos.repuestos().stream()
                        .map(r -> new RepuestoOrden(r.repuestoId(), r.cantidad()))
                        .toList();

            orden.actualizarRepuestos(repuestos, solicitanteId);

        } else if (datos.tecnicoId() != null
                && !datos.tecnicoId().trim().equals(tecnico)) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El técnico se modifica al asignar la orden"
            );
        }
        if (siguiente != EstadoOrden.CREADA && siguiente != EstadoOrden.CANCELADA
                && (tecnico == null || tecnico.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes asignar un técnico");
        }
        if (actual == siguiente && java.util.Objects.equals(tecnico, orden.getTecnicoId())) return orden;
        orden.cambiarEstado(siguiente, tecnico, solicitanteId);
        return repositorio.save(orden);
    }

    @Transactional
    public void eliminarOrden(Long id, String solicitanteId) {
        var orden = ordenPropiaParaModificar(id, solicitanteId);
        if (!"CREADA".equals(orden.getEstado()) && !"CANCELADA".equals(orden.getEstado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo se pueden eliminar órdenes creadas o canceladas");
        }
        repositorio.delete(orden);
    }

    private OrdenTrabajo ordenPropiaParaModificar(Long id, String solicitanteId) {
        // Bloqueo hasta commit: evita editar/eliminar durante un cambio de estado concurrente.
        var orden = repositorio.findByIdParaModificar(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Orden no encontrada"));
        if (solicitanteId == null || !solicitanteId.equals(orden.getSolicitanteId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Orden no encontrada");
        }
        return orden;
    }
}
