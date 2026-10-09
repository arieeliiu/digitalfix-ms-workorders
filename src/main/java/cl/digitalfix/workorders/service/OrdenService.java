package cl.digitalfix.workorders.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import cl.digitalfix.workorders.client.CatalogoClient;
import cl.digitalfix.workorders.dto.request.ActualizarOrdenRequest;
import cl.digitalfix.workorders.dto.request.CambioEstadoRequest;
import cl.digitalfix.workorders.dto.request.NuevaOrdenRequest;
import cl.digitalfix.workorders.dto.response.OrdenResponse;
import cl.digitalfix.workorders.entity.EstadoOrden;
import cl.digitalfix.workorders.entity.OrdenTrabajo;
import cl.digitalfix.workorders.entity.RepuestoOrden;
import cl.digitalfix.workorders.mapper.OrdenMapper;
import cl.digitalfix.workorders.repository.OrdenRepository;

import static cl.digitalfix.workorders.mapper.OrdenMapper.respuesta;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrdenService {

    private final OrdenRepository repositorio;
    private final CatalogoClient catalogCliente;

    @Transactional
    public OrdenResponse crearOrden(NuevaOrdenRequest solicitud) {
        var orden = new OrdenTrabajo(
                solicitud.servicioId(),
                solicitud.descripcion().trim(),
                solicitud.direccion().trim(),
                solicitud.solicitanteId().trim()
        );

        return respuesta(repositorio.save(orden));
    }

    public OrdenResponse consultarOrden(Long id) {
        return respuesta(repositorio.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No existe una orden con id " + id
                )));
    }

    public List<OrdenResponse> listarOrdenes() {
        return repositorio.findAll().stream()
                .map(OrdenMapper::respuesta)
                .toList();
    }

    public List<OrdenResponse> listarOrdenesDelSolicitante(
            String solicitanteId) {

        return repositorio.findBySolicitanteId(solicitanteId).stream()
                .map(OrdenMapper::respuesta)
                .toList();
    }

    @Transactional
    public OrdenResponse actualizarOrden(
            Long id,
            ActualizarOrdenRequest datos,
            String solicitanteId) {

        var orden = ordenPropiaParaModificar(
                id,
                solicitanteId);

        if (!"CREADA".equals(orden.getEstado())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Solo se pueden editar órdenes creadas");
        }

        orden.actualizar(
                datos.servicioId(),
                datos.descripcion().trim(),
                datos.direccion().trim(),
                solicitanteId);

        return respuesta(repositorio.save(orden));
    }

    @Transactional
    public OrdenResponse cambiarEstado(
            Long id,
            CambioEstadoRequest datos,
            String actorId,
            boolean puedeGestionarOrdenes) {

        var orden = ordenParaGestionar(
                id,
                actorId,
                puedeGestionarOrdenes);

        var actual = EstadoOrden.valueOf(
                orden.getEstado());

        var siguiente = datos.status();

        if (!actual.permite(siguiente)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Transición de estado no permitida");
        }

        // Un reintento del mismo estado no debe modificar repuestos
        // ni volver a descontar stock.
        if (actual == siguiente) {
            return respuesta(orden);
        }

        String tecnico = orden.getTecnicoId();

        if (siguiente == EstadoOrden.ASIGNADA) {

            if (datos.tecnicoId() != null) {
                tecnico = datos.tecnicoId().trim();
            }

            if (tecnico == null || tecnico.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Debes asignar un técnico");
            }

            var repuestosSolicitud =
                    datos.repuestos() == null
                            ? List.<cl.digitalfix.workorders.dto.request.RepuestoRequest>of()
                            : datos.repuestos();

            // Si la orden no utiliza repuestos no se llama a Catalog.
            if (!repuestosSolicitud.isEmpty()) {
                catalogCliente.descontarStock(
                        orden.getId(),
                        repuestosSolicitud);
            }

            var repuestos = repuestosSolicitud.stream()
                    .map(repuesto -> new RepuestoOrden(
                            repuesto.repuestoId(),
                            repuesto.cantidad()))
                    .toList();

            orden.actualizarRepuestos(
                    repuestos,
                    actorId);

        } else {

            if (datos.tecnicoId() != null
                    && !datos.tecnicoId()
                            .trim()
                            .equals(tecnico)) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "El técnico se modifica al asignar la orden");
            }

            if (siguiente != EstadoOrden.CREADA
                    && siguiente != EstadoOrden.CANCELADA
                    && (tecnico == null || tecnico.isBlank())) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Debes asignar un técnico");
            }
        }

        orden.cambiarEstado(
                siguiente,
                tecnico,
                actorId);

        return respuesta(repositorio.save(orden));
    }

    @Transactional
    public void eliminarOrden(
            Long id,
            String solicitanteId) {

        var orden = ordenPropiaParaModificar(
                id,
                solicitanteId);

        if (!"CREADA".equals(orden.getEstado())
                && !"CANCELADA".equals(orden.getEstado())) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Solo se pueden eliminar órdenes creadas o canceladas");
        }

        repositorio.delete(orden);
    }

    private OrdenTrabajo ordenPropiaParaModificar(
            Long id,
            String solicitanteId) {

        var orden = repositorio.findByIdParaModificar(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Orden no encontrada"));

        if (solicitanteId == null
                || !solicitanteId.equals(
                        orden.getSolicitanteId())) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Orden no encontrada");
        }

        return orden;
    }

    private OrdenTrabajo ordenParaGestionar(
            Long id,
            String actorId,
            boolean puedeGestionarOrdenes) {

        var orden = repositorio.findByIdParaModificar(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Orden no encontrada"));

        if (!puedeGestionarOrdenes
                && (actorId == null
                    || !actorId.equals(
                            orden.getSolicitanteId()))) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Orden no encontrada");
        }

        return orden;
    }
}
