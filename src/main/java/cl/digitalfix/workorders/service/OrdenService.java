package cl.digitalfix.workorders.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import cl.digitalfix.workorders.client.CatalogoClient;
import cl.digitalfix.workorders.dto.request.ActualizarOrdenRequest;
import cl.digitalfix.workorders.dto.request.ActualizarRepuestosRequest;
import cl.digitalfix.workorders.dto.request.RepuestoRequest;
import java.util.Map;
import java.util.TreeMap;
import cl.digitalfix.workorders.dto.request.CambioEstadoRequest;
import cl.digitalfix.workorders.dto.request.ContactosRequest;
import cl.digitalfix.workorders.dto.request.NuevaOrdenRequest;
import cl.digitalfix.workorders.dto.response.OrdenResponse;
import cl.digitalfix.workorders.entity.EstadoOrden;
import cl.digitalfix.workorders.entity.OrdenTrabajo;
import cl.digitalfix.workorders.entity.RepuestoOrden;
import cl.digitalfix.workorders.mapper.OrdenMapper;
import cl.digitalfix.workorders.messaging.NotificacionesOrden;
import cl.digitalfix.workorders.repository.OrdenRepository;

import static cl.digitalfix.workorders.mapper.OrdenMapper.respuesta;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrdenService {

    private final OrdenRepository repositorio;
    private final CatalogoClient catalogCliente;
    private final NotificacionesOrden notificaciones;
    private final jakarta.validation.Validator validador;

    @Transactional
    public OrdenResponse crearOrden(NuevaOrdenRequest solicitud) {
        exigirCorreo(solicitud.emailContacto());
        var orden = new OrdenTrabajo(
                solicitud.servicioId(),
                solicitud.descripcion().trim(),
                solicitud.direccion().trim(),
                solicitud.solicitanteId().trim()
        );

        orden.actualizarContactos(solicitud.emailContacto(), null);
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
            String solicitanteId, boolean esAdmin) {

        var orden = ordenParaGestionar(
                id,
                solicitanteId, esAdmin);

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

            exigirCorreo(datos.emailTecnico());
            orden.actualizarContactos(null, datos.emailTecnico());

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

        var instantanea = respuesta(repositorio.save(orden));
        notificaciones.despuesDelCommit(instantanea);
        return instantanea;
    }

    @Transactional
    public OrdenResponse actualizarContactos(Long id,
            ContactosRequest datos,
            String actorId, boolean gestor) {
        var orden = ordenParaGestionar(id, actorId, gestor);
        if (!gestor && datos.emailTecnico() != null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El correo del técnico requiere gestión de órdenes");
        if (datos.emailContacto() != null) exigirCorreo(datos.emailContacto());
        if (datos.emailTecnico() != null && (orden.getTecnicoId() != null || !datos.emailTecnico().isEmpty()))
            exigirCorreo(datos.emailTecnico());
        orden.actualizarContactos(datos.emailContacto(), datos.emailTecnico());
        return respuesta(repositorio.save(orden));
    }

    // Ajusta las cantidades absolutas con un estado anterior esperado.
    // El stock se concilia en Catalog sin repetir descuentos al reintentar.
    @Transactional
    public OrdenResponse actualizarRepuestos(Long id, ActualizarRepuestosRequest solicitud,
            String actorId, boolean gestor) {
        if (!gestor) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var orden = ordenParaGestionar(id, actorId, true);
        var estado = EstadoOrden.valueOf(orden.getEstado());
        if (estado != EstadoOrden.ASIGNADA && estado != EstadoOrden.EN_DESPLAZAMIENTO
                && estado != EstadoOrden.EN_EJECUCION)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Los repuestos solo pueden editarse en órdenes asignadas o en ejecución");
        var anteriores = normalizarRepuestos(solicitud.anteriores());
        var nuevos = normalizarRepuestos(solicitud.nuevos());
        var actual = new TreeMap<Long, Integer>();
        orden.getRepuestos().forEach(r -> actual.merge(r.getRepuestoId(), r.getCantidad(), Integer::sum));
        // Reintento tras éxito: la versión deseada ya está guardada.
        if (actual.equals(nuevos)) return respuesta(orden);
        if (!actual.equals(anteriores))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Los repuestos cambiaron. Actualiza la orden antes de editar.");
        var previos = actual.entrySet().stream().map(e -> new RepuestoRequest(e.getKey(), e.getValue())).toList();
        var destino = nuevos.entrySet().stream().map(e -> new RepuestoRequest(e.getKey(), e.getValue())).toList();
        catalogCliente.ajustarStock(id, new ActualizarRepuestosRequest(previos, destino));
        var entidades = destino.stream().map(r -> new RepuestoOrden(r.repuestoId(), r.cantidad())).toList();
        orden.actualizarRepuestos(entidades, actorId);
        return respuesta(repositorio.save(orden));
    }

    private Map<Long, Integer> normalizarRepuestos(List<RepuestoRequest> lista) {
        if (lista == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var resultado = new TreeMap<Long, Integer>();
        for (var repuesto : lista) {
            if (repuesto == null || repuesto.repuestoId() == null || repuesto.repuestoId() <= 0
                    || repuesto.cantidad() == null || repuesto.cantidad() <= 0
                    || resultado.putIfAbsent(repuesto.repuestoId(), repuesto.cantidad()) != null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Repuesto duplicado o cantidad inválida");
        }
        return resultado;
    }

    @Transactional
    public void eliminarOrden(
            Long id,
            String solicitanteId, boolean esAdmin) {

        var orden = ordenParaGestionar(
                id,
                solicitanteId, esAdmin);

        if (!"CREADA".equals(orden.getEstado())
                && !"CANCELADA".equals(orden.getEstado())) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Solo se pueden eliminar órdenes creadas o canceladas");
        }

        repositorio.delete(orden);
    }

    private void exigirCorreo(String correo) {
        if (!validador.validateValue(NuevaOrdenRequest.class, "emailContacto", correo).isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Se requiere un correo válido de hasta 254 caracteres");
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
