package cl.digitalfix.workorders.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

// Solo la omisión conserva el valor; un null explícito no es un correo.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContactosRequest(@Email @Size(max=254) @Pattern(regexp="(?s).*\\S.*") String emailContacto,
                               @Email @Size(max=254) String emailTecnico) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ContactosRequest desdeJson(Map<String, Object> campos) {
        return new ContactosRequest(correo(campos, "emailContacto"), correo(campos, "emailTecnico"));
    }

    private static String correo(Map<String, Object> campos, String nombre) {
        if (!campos.containsKey(nombre)) return null;
        if (campos.get(nombre) instanceof String valor) return valor;
        throw new IllegalArgumentException("El correo debe ser una cadena válida");
    }
}
