package cl.digitalfix.workorders.client;

import java.util.List;
import java.util.Objects;
import java.math.BigDecimal;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import cl.digitalfix.workorders.dto.request.DescontarStockCatalogoRequest;
import cl.digitalfix.workorders.dto.request.RepuestoRequest;

@Component
public class CatalogoClient {

    public record Servicio(Long id, String nombre, String descripcion, BigDecimal tarifa) {}

    public String nombreServicio(Long id) {
        var peticion = restClient.get().uri("/api/catalog/services");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt)
            peticion.headers(h -> h.setBearerAuth(jwt.getToken().getTokenValue()));
        var servicios = peticion.retrieve().body(new ParameterizedTypeReference<List<Servicio>>() {});
        return servicios == null ? null : servicios.stream().filter(s -> id.equals(s.id()))
                .map(Servicio::nombre).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private final RestClient restClient;

    public CatalogoClient(@Qualifier("catalogRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public void descontarStock(
            Long ordenId,
            List<RepuestoRequest> repuestos) {

        var solicitud = new DescontarStockCatalogoRequest(
                ordenId,
                repuestos);

        try {
            restClient.post()
                    .uri("/api/catalog/spare-parts/discount-stock")
                    .body(solicitud)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Catalog no encontró uno de los repuestos solicitados");
        } catch (HttpClientErrorException.Conflict ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Catalog rechazó el descuento por stock insuficiente o conflicto con la orden");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Catalog no se encuentra disponible",
                    ex);
        }
    }
}
