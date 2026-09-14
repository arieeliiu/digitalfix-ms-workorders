package cl.digitalfix.workorders;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.digitalfix.workorders.controller.OrdenTrabajoControlador;
import cl.digitalfix.workorders.entity.OrdenTrabajo;
import cl.digitalfix.workorders.repository.OrdenTrabajoRepositorio;
import cl.digitalfix.workorders.service.OrdenTrabajoServicio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrdenTrabajoControlador.class)
@Import(OrdenTrabajoServicio.class)
class OrdenTrabajoControladorTests {

    @Autowired
    private MockMvc cliente;

    // Solo simulamos la persistencia; el controlador y servicio son reales.
    @MockitoBean
    private OrdenTrabajoRepositorio repositorio;

    @Test
    void crearOrdenRetorna201() throws Exception {
        when(repositorio.save(any(OrdenTrabajo.class)))
            .thenAnswer(llamada -> llamada.getArgument(0));

        cliente.perform(post("/api/workorders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "servicioId": 1,
                      "descripcion": " Enchufe sin suministro ",
                      "direccion": " Calle 123 ",
                      "solicitanteId": "usuario-prueba"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.servicioId").value(1))
            .andExpect(jsonPath("$.descripcion").value("Enchufe sin suministro"))
            .andExpect(jsonPath("$.direccion").value("Calle 123"))
            .andExpect(jsonPath("$.solicitanteId").value("usuario-prueba"));

        verify(repositorio).save(any(OrdenTrabajo.class));
    }

    @Test
    void consultarOrdenExistenteRetorna200() throws Exception {
        when(repositorio.findById(1L)).thenReturn(Optional.of(ordenEjemplo()));

        cliente.perform(get("/api/workorders/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.descripcion").value("Enchufe sin suministro"));
    }

    @Test
    void consultarOrdenInexistenteRetorna404() throws Exception {
        when(repositorio.findById(999L)).thenReturn(Optional.empty());

        cliente.perform(get("/api/workorders/999"))
            .andExpect(status().isNotFound());
    }

    @Test
    void listarOrdenesRetorna200() throws Exception {
        when(repositorio.findAll()).thenReturn(List.of(ordenEjemplo()));

        cliente.perform(get("/api/workorders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].servicioId").value(1));
    }

    @ParameterizedTest
    @MethodSource("solicitudesInvalidas")
    void rechazarDatosInvalidosSinGuardar(String cuerpo) throws Exception {
        cliente.perform(post("/api/workorders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(repositorio);
    }

    static Stream<String> solicitudesInvalidas() {
        String valido = """
            {
              "servicioId": 1,
              "descripcion": "Problema",
              "direccion": "Calle",
              "solicitanteId": "usuario"
            }
            """;

        return Stream.of(
            "{}",
            valido.replace("\"servicioId\": 1", "\"servicioId\": 0"),
            valido.replace("\"Problema\"", "\" \""),
            valido.replace("\"Calle\"", "\" \""),
            valido.replace("\"usuario\"", "\" \""),
            valido.replace("Problema", "x".repeat(1001)),
            valido.replace("Calle", "x".repeat(301)),
            valido.replace("usuario", "x".repeat(101))
        );
    }

    private OrdenTrabajo ordenEjemplo() {
        return new OrdenTrabajo(
            1L, "Enchufe sin suministro", "Calle 123", "usuario-prueba"
        );
    }
}