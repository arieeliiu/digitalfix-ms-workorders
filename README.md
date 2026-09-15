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
| DB_HOST | Endpoint Oracle RDS |
| DB_PORT | Puerto, por defecto 1521 |
| DB_SERVICE | Service name, por defecto ORCL |
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

Ejemplo de creación interna (solo BFF, nunca desde Angular):

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

Las pruebas automatizadas cubren controlador/validación con repositorio simulado,
contexto con H2 y persistencia JPA real en H2 en disco al cerrar/reabrir la aplicación.
No requieren credenciales Oracle. La demostración Oracle RDS después de reiniciar
el contenedor debe repetirse con este despliegue y usuarios reales.

## Despliegue integrado

El BFF comprueba Catalog, obtiene oid del JWT y limita consultas a órdenes propias.
Workorders recibe solicitanteId únicamente por la red Docker de confianza.
No valida tokens por sí mismo y no debe publicar 8082.

Ver [guía completa](../digitalfix-ms-bff/DEPLOYMENT.md) y
[resultados de validación](../digitalfix-ms-bff/VERIFICATION.md).