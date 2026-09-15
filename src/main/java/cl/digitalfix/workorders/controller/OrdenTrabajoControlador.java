package cl.digitalfix.workorders.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import cl.digitalfix.workorders.dto.CrearOrdenSolicitud;
import cl.digitalfix.workorders.dto.ActualizarOrdenSolicitud;
import cl.digitalfix.workorders.dto.CambiarEstadoSolicitud;
import cl.digitalfix.workorders.entity.OrdenTrabajo;
import cl.digitalfix.workorders.service.OrdenTrabajoServicio;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/workorders")
public class OrdenTrabajoControlador {

    private final OrdenTrabajoServicio servicio;

    public OrdenTrabajoControlador(OrdenTrabajoServicio servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrdenTrabajo crearOrden(
            @Valid @RequestBody CrearOrdenSolicitud solicitud) {
        return servicio.crearOrden(solicitud);
    }

    @GetMapping("/{id}")
    public OrdenTrabajo consultarOrden(@PathVariable Long id) {
        return servicio.consultarOrden(id);
    }

    @GetMapping
    public List<OrdenTrabajo> listarOrdenes(@RequestParam(required = false) String solicitanteId) {
        // Parámetro interno: el BFF lo deriva del JWT, nunca del formulario público.
        return solicitanteId == null ? servicio.listarOrdenes()
            : servicio.listarOrdenesDelSolicitante(solicitanteId);
    }

    @PutMapping("/{id}")
    public OrdenTrabajo actualizarOrden(@PathVariable Long id,
            @Valid @RequestBody ActualizarOrdenSolicitud solicitud, @RequestParam String solicitanteId) {
        return servicio.actualizarOrden(id, solicitud, solicitanteId);
    }

    @PutMapping("/{id}/status")
    public OrdenTrabajo cambiarEstado(@PathVariable Long id,
            @Valid @RequestBody CambiarEstadoSolicitud solicitud, @RequestParam String solicitanteId) {
        return servicio.cambiarEstado(id, solicitud, solicitanteId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarOrden(@PathVariable Long id, @RequestParam String solicitanteId) {
        servicio.eliminarOrden(id, solicitanteId);
    }
}
