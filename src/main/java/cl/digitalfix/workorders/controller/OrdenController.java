package cl.digitalfix.workorders.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import cl.digitalfix.workorders.dto.request.ActualizarOrdenRequest;
import cl.digitalfix.workorders.dto.request.ActualizarRepuestosRequest;
import cl.digitalfix.workorders.dto.request.CambioEstadoRequest;
import cl.digitalfix.workorders.dto.request.ContactosRequest;
import cl.digitalfix.workorders.dto.request.NuevaOrdenRequest;
import cl.digitalfix.workorders.dto.response.HistorialOrdenResponse;
import cl.digitalfix.workorders.dto.response.OrdenResponse;
import cl.digitalfix.workorders.service.ActorAuditoria;
import cl.digitalfix.workorders.service.OrdenService;

@RestController
@RequestMapping("/api/workorders")
@RequiredArgsConstructor
public class OrdenController {

    // Roles que se registran en el historial, en orden fijo.
    private static final List<String> ROLES_AUDITABLES = List.of("Admin", "Operador", "Cliente", "Auditor");
    private static final List<String> ROLES_LECTURA_HISTORIAL = List.of("Admin", "Auditor");

    private final OrdenService servicio;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrdenResponse crearOrden(
            @Valid @RequestBody NuevaOrdenRequest solicitud,
            JwtAuthenticationToken autenticacion) {

        var actor = new ActorAuditoria(actorAutenticado(autenticacion), rolesAuditoria(autenticacion));
        if (!esAdmin(autenticacion) && !actor.id().equals(solicitud.solicitanteId().trim()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes crear órdenes a nombre de otro usuario");
        return servicio.crearOrden(solicitud, actor);
    }

    // Trazabilidad de la orden (creación, cambios de estado y eliminación).
    // Solo lectura, para Admin y Auditor.
    @GetMapping("/{id}/historial")
    public List<HistorialOrdenResponse> consultarHistorial(
            @PathVariable Long id,
            JwtAuthenticationToken autenticacion) {

        if (autenticacion == null || !autenticacion.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var roles = autenticacion.getToken().getClaimAsStringList("roles");
        if (roles == null || roles.stream().noneMatch(ROLES_LECTURA_HISTORIAL::contains))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return servicio.consultarHistorial(id);
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
            JwtAuthenticationToken autenticacion) {

        return servicio.actualizarOrden(
                id,
                solicitud,
                new ActorAuditoria(actorAutenticado(autenticacion), rolesAuditoria(autenticacion)), esAdmin(autenticacion),
                puedeGestionarOrdenes(autenticacion));
    }

    @PutMapping("/{id}/status")
    public OrdenResponse cambiarEstado(
            @PathVariable Long id,
            @Valid @RequestBody CambioEstadoRequest solicitud,
            @RequestParam String solicitanteId,
            JwtAuthenticationToken autenticacion) {

        // El historial toma el actor del JWT (oid), no del query param.
        var auditor = new ActorAuditoria(
                actorAutenticado(autenticacion),
                rolesAuditoria(autenticacion));

        return servicio.cambiarEstado(
                id,
                solicitud,
                // El parámetro se conserva por compatibilidad; no concede propiedad sobre la orden.
                auditor.id(),
                puedeGestionarOrdenes(autenticacion),
                auditor);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarOrden(
            @PathVariable Long id,
            JwtAuthenticationToken autenticacion) {

        servicio.eliminarOrden(
                id,
                actorAutenticado(autenticacion), esAdmin(autenticacion),
                rolesAuditoria(autenticacion));
    }

    private String rolesAuditoria(JwtAuthenticationToken autenticacion) {
        if (autenticacion == null) return null;
        var roles = autenticacion.getToken().getClaimAsStringList("roles");
        if (roles == null) return null;
        var conocidos = ROLES_AUDITABLES.stream().filter(roles::contains).toList();
        return conocidos.isEmpty() ? null : String.join(",", conocidos);
    }

    private String actorAutenticado(JwtAuthenticationToken autenticacion) {
        if (autenticacion == null || !autenticacion.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var jwt = autenticacion.getToken();
        String oid = jwt.getClaimAsString("oid");
        var roles = jwt.getClaimAsStringList("roles");
        if (oid == null || oid.isBlank() || oid.length() > 100 || roles == null
                || roles.stream().noneMatch(java.util.List.of("Admin", "Operador", "Cliente")::contains))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return oid;
    }

    private boolean esAdmin(JwtAuthenticationToken autenticacion) {
        return autenticacion.getToken().getClaimAsStringList("roles").contains("Admin");
    }

    private boolean puedeGestionarOrdenes(
            JwtAuthenticationToken autenticacion) {

        var roles = autenticacion.getToken()
                .getClaimAsStringList("roles");

        return roles != null
                && (roles.contains("Operador")
                    || roles.contains("Admin"));
    }

    @PutMapping("/{id}/repuestos")
    public OrdenResponse actualizarRepuestos(@PathVariable Long id,
            @Valid @RequestBody ActualizarRepuestosRequest solicitud,
            JwtAuthenticationToken autenticacion) {
        return servicio.actualizarRepuestos(id, solicitud,
                new ActorAuditoria(actorAutenticado(autenticacion), rolesAuditoria(autenticacion)),
                puedeGestionarOrdenes(autenticacion));
    }

    @PatchMapping("/{id}/contactos")
    public OrdenResponse actualizarContactos(@PathVariable Long id,
            @Valid @RequestBody ContactosRequest solicitud,
            JwtAuthenticationToken autenticacion) {
        if (autenticacion == null || !autenticacion.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String oid = autenticacion.getToken().getClaimAsString("oid");
        if (oid == null || oid.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return servicio.actualizarContactos(id, solicitud,
                new ActorAuditoria(actorAutenticado(autenticacion), rolesAuditoria(autenticacion)),
                puedeGestionarOrdenes(autenticacion));
    }
}
