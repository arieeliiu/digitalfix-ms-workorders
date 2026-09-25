# DigitalFix — Workorders

## Integración con BFF

GET /api/workorders acepta el parámetro interno `solicitanteId` para filtrar en Oracle.

El BFF deriva ese valor desde el JWT validado y también obtiene desde el token el solicitante utilizado al crear órdenes.

Workorders recibe estas llamadas únicamente desde la red interna y no debe exponerse directamente al exterior.

Microservicio encargado de administrar órdenes de mantenimiento eléctrico.

## Integrantes

- Ariel Molina.
- Lucas Ferrada.

## Tecnologías

- Java 21
- Spring Boot 4.1.1
- Maven
- Spring Data JPA
- Oracle Database en Amazon RDS

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

El servicio utiliza el puerto `8082`.

Durante el desarrollo, Hibernate actualiza el esquema mediante:

```text
spring.jpa.hibernate.ddl-auto=update
```

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
| PUT | /api/workorders/{id} | Actualiza una orden creada |
| PUT | /api/workorders/{id}/status | Cambia el estado de una orden |
| DELETE | /api/workorders/{id} | Elimina una orden creada o cancelada |

Ejemplo de creación interna desde el BFF:

```json
{
  "servicioId": 1,
  "descripcion": "Enchufe sin suministro",
  "direccion": "Calle de prueba 123",
  "solicitanteId": "usuario-prueba"
}
```

El servidor genera automáticamente:

- identificador;
- fecha de creación;
- estado inicial `CREADA`.

Los campos inválidos reciben una respuesta `400 Bad Request`.

## Estados de una orden

El flujo implementado considera:

```text
CREADA
→ ASIGNADA
→ EN_DESPLAZAMIENTO
→ EN_EJECUCION
→ CERRADA
```

Una orden también puede pasar a:

```text
CANCELADA
```

No se permite avanzar a estados posteriores sin haber asignado previamente un técnico.

## Repuestos asociados a una orden

Una orden puede almacenar los repuestos que serán utilizados durante el trabajo.

Cada repuesto asociado se registra mediante:

```text
repuestoId
cantidad
```

Ejemplo al asignar una orden:

```json
{
  "status": "ASIGNADA",
  "tecnicoId": "tecnico-01",
  "repuestos": [
    {
      "repuestoId": 4,
      "cantidad": 2
    },
    {
      "repuestoId": 7,
      "cantidad": 1
    }
  ]
}
```

Workorders almacena únicamente:

- el identificador del repuesto;
- la cantidad utilizada.

El nombre, descripción y stock del repuesto pertenecen a `ms-digitalfix-catalog`.

La relación se persiste asociada a la orden en la tabla:

```text
orden_repuestos
```

## Integración de stock pendiente

Actualmente Workorders puede almacenar los repuestos asociados a una orden, pero todavía no realiza la coordinación completa de stock con Catalog.

Queda pendiente implementar:

- verificar que el repuesto exista en Catalog;
- verificar que exista stock suficiente;
- descontar el stock cuando la orden pase a `ASIGNADA`;
- evitar stock negativo;
- evitar descuentos duplicados para una misma orden.

Esta lógica se implementará mediante integración entre `ms-digitalfix-workorders` y `ms-digitalfix-catalog`.

## Verificación

```powershell
.\mvnw.cmd verify
```

Las pruebas automatizadas cubren controlador y validaciones, contexto con H2 y persistencia JPA.

Además, se verificó que la incorporación de repuestos asociados a una orden compile correctamente sin afectar las pruebas existentes.

No requieren credenciales Oracle.

La comprobación real contra Oracle RDS debe repetirse después de desplegar esta versión.

## Despliegue integrado

Workorders funciona como microservicio interno.

El flujo esperado es:

```text
Frontend
→ API Gateway
→ BFF
→ Workorders
→ Oracle RDS
```

Catalog y Workorders se comunican dentro de la infraestructura interna para coordinar funcionalidades de dominio.

Workorders no debe publicar directamente el puerto `8082` hacia Internet.

La orquestación del despliegue se gestiona desde `digitalfix-infra`.