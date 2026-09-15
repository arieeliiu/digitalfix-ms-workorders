# DigitalFix — Workorders

## Integración con BFF

GET /api/workorders acepta el parámetro interno solicitanteId para filtrar en
Oracle. El BFF deriva ese valor y el solicitante de creación del JWT validado,
y verifica propiedad en la consulta individual. Workorders aún no valida JWT:
mantenerlo en la red privada de Docker, sin publicar su puerto al exterior.

Microservicio para crear y consultar órdenes de mantenimiento eléctrico.

## Integrantes

- Ariel Molina.
- Lucas Ferrada.

## Tecnologías

Java 21, Spring Boot 4.1.1, Maven, Spring Data JPA y Oracle Database en Amazon RDS.

## Configuración

Definir estas variables de entorno antes de iniciar:

| Variable | Contenido |
|---|---|
| DB_URL | URL JDBC de Oracle |
| DB_USERNAME | Usuario de la base de datos |
| DB_PASSWORD | Contraseña del usuario |

Formato de conexión:

```text
jdbc:oracle:thin:@//HOST:1521/SERVICIO
```

No guardar credenciales en el repositorio.

El servicio utiliza el puerto 8082.
Durante el desarrollo, Hibernate actualiza el esquema mediante
`spring.jpa.hibernate.ddl-auto=update`.

## Ejecución

```powershell
.\mvnw.cmd spring-boot:run
```

## Endpoints

| Método | Ruta | Resultado |
|---|---|---|
| POST | /api/workorders | Crea una orden; responde 201 |
| GET | /api/workorders/{id} | Consulta una orden; responde 200 o 404 |
| GET | /api/workorders | Lista las órdenes; responde 200 |

Ejemplo de creación:

```json
{
  "servicioId": 1,
  "descripcion": "Enchufe sin suministro",
  "direccion": "Calle de prueba 123",
  "solicitanteId": "usuario-prueba"
}
```

El servidor genera el identificador, la fecha y el estado inicial CREADA.
Los campos inválidos reciben una respuesta 400.

## Verificación

```powershell
.\mvnw.cmd verify
```

Las pruebas automatizadas cubren creación, consulta, listado y errores
de validación, utilizando un repositorio simulado.
La prueba inicial de contexto requiere acceso a Oracle y sus variables.

Se comprobó manualmente la creación y recuperación de una orden
desde Oracle RDS después de reiniciar el servicio.

## Integración pendiente

- Comprobar el servicio solicitado contra Catalog.
- Obtener el solicitante desde la identidad autenticada.
- Restringir las consultas de Cliente a sus propias órdenes.
- Integrar con el BFF y API Gateway.
- Desplegar mediante Docker en EC2.

Actualmente, solicitanteId se recibe para pruebas internas.
El servicio todavía no implementa autenticación ni autorización y
no debe exponerse públicamente sin controles de acceso.
