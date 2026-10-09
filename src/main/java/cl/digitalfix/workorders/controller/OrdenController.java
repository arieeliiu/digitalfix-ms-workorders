package cl.digitalfix.workorders.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import cl.digitalfix.workorders.dto.request.ActualizarOrdenRequest;
import cl.digitalfix.workorders.dto.request.CambioEstadoRequest;
import cl.digitalfix.workorders.dto.request.NuevaOrdenRequest;
import cl.digitalfix.workorders.dto.response.OrdenResponse;
import cl.digitalfix.workorders.service.OrdenService;

@RestController
@RequestMapping("/api/workorders")
@RequiredArgsConstructor
public class OrdenController {

    private final OrdenService servicio;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrdenResponse crearOrden(
            @Valid @RequestBody NuevaOrdenRequest solicitud) {

        return servicio.crearOrden(solicitud);
    }

    @GetMapping("/{id}")
    public OrdenResponse consultarOrden(
            @PathVariable Long id) {

        return servicio.consultarOrden(id);
    }

    @GetMapping
    public List<OrdenResponse> listarOrdenes(
            @RequestParam(required = false) String solicitanteId) {

        // Parámetro interno: el BFF lo deriva del JWT.
        return solicitanteId == null
                ? servicio.listarOrdenes()
                : servicio.listarOrdenesDelSolicitante(solicitanteId);
    }

    @PutMapping("/{id}")
    public OrdenResponse actualizarOrden(
            @PathVariable Long id,
            @Valid @RequestBody ActualizarOrdenRequest solicitud,
            @RequestParam String solicitanteId) {

        return servicio.actualizarOrden(
                id,
                solicitud,
                solicitanteId);
    }

    @PutMapping("/{id}/status")
    public OrdenResponse cambiarEstado(
            @PathVariable Long id,
            @Valid @RequestBody CambioEstadoRequest solicitud,
            @RequestParam String solicitanteId,
            JwtAuthenticationToken autenticacion) {

        return servicio.cambiarEstado(
                id,
                solicitud,
                solicitanteId,
                puedeGestionarOrdenes(autenticacion));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarOrden(
            @PathVariable Long id,
            @RequestParam String solicitanteId) {

        servicio.eliminarOrden(
                id,
                solicitanteId);
    }

    private boolean puedeGestionarOrdenes(
            JwtAuthenticationToken autenticacion) {

        var roles = autenticacion.getToken()
                .getClaimAsStringList("roles");

        return roles != null
                && (roles.contains("Operador")
                    || roles.contains("Admin"));
    }
}
