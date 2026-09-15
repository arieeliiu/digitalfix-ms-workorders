package cl.digitalfix.workorders.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import cl.digitalfix.workorders.dto.CrearOrdenSolicitud;
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
}
