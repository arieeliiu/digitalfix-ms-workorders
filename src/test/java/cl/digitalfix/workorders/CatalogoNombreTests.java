package cl.digitalfix.workorders;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import cl.digitalfix.workorders.client.CatalogoClient;
import static org.junit.jupiter.api.Assertions.*;

class CatalogoNombreTests {
    @Test void consultaApiExistenteYPropagaBearerSoloPorHttp() throws Exception {
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/catalog/services", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body="[{\"id\":2,\"nombre\":\"Revisión eléctrica\",\"descripcion\":\"Servicio\",\"tarifa\":1000}]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var jwt=Jwt.withTokenValue("token-sintetico").header("alg","RS256").claim("oid","operador").build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
            var client=new CatalogoClient(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build());
            assertEquals("Revisión eléctrica",client.nombreServicio(2L));
            assertEquals("Bearer token-sintetico",authorization.get());
            assertNull(client.nombreServicio(999L));
        } finally { server.stop(0); SecurityContextHolder.clearContext(); }
    }
}
