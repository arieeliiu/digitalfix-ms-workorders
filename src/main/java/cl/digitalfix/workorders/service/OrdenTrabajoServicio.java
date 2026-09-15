package cl.digitalfix.workorders.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import cl.digitalfix.workorders.dto.CrearOrdenSolicitud;
import cl.digitalfix.workorders.entity.OrdenTrabajo;
import cl.digitalfix.workorders.repository.OrdenTrabajoRepositorio;

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
}
