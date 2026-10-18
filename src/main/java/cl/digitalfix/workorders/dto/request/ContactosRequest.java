package cl.digitalfix.workorders.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

// null conserva el valor actual; cadena vacía elimina un contacto obsoleto.
public record ContactosRequest(@Email @Size(max=254) String emailContacto,
                               @Email @Size(max=254) String emailTecnico) {}
