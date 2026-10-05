# DigitalFix — Workorders

Microservicio encargado de administrar las órdenes de trabajo de DigitalFix, controlar su ciclo de estados y coordinar el descuento de repuestos con Catalog al momento de asignar una orden.

## Integrantes

- Ariel Molina
- Lucas Ferrada

## Tecnologías

- Java 21
- Spring Boot 4.1.1
- Maven
- Spring Data JPA
- Spring Web MVC
- Spring Security OAuth2 Resource Server
- Oracle Database en Amazon RDS
- H2 para pruebas

## Integración con BFF

Workorders funciona como un microservicio interno.

El BFF realiza las llamadas hacia Workorders después de recibir las solicitudes provenientes del frontend.

El endpoint:

```http
GET /api/workorders
```

acepta el parámetro interno:

```text
solicitanteId
```

para filtrar las órdenes correspondientes a un usuario.

El BFF obtiene este identificador desde el JWT validado y lo utiliza también al crear o modificar órdenes.

Workorders no debe exponerse directamente a Internet.

## Configuración

El servicio utiliza por defecto el puerto:

```text
8082
```

La comunicación con Catalog se configura mediante:

```text
CATALOG_URL
```

Si la variable no está definida, durante el desarrollo local se utiliza:

```text
http://localhost:8081
```

La propiedad utilizada es:

```properties
catalog.url=${CATALOG_URL:http://localhost:8081}
```

La conexión a Oracle se configura mediante las propiedades de datasource de Spring.

Las credenciales de base de datos no deben mantenerse permanentemente dentro del repositorio y deben migrarse a variables de entorno antes del despliegue final.

Durante el desarrollo, Hibernate actualiza el esquema mediante:

```text
spring.jpa.hibernate.ddl-auto=update
```

## Ejecución local

Ejecutar:

```powershell
.\mvnw.cmd spring-boot:run
```

El servicio queda disponible por defecto en:

```text
http://localhost:8082
```

## Endpoints

| Método | Ruta | Resultado |
|---|---|---|
| POST | `/api/workorders` | Crea una orden |
| GET | `/api/workorders/{id}` | Consulta una orden |
| GET | `/api/workorders` | Lista órdenes |
| PUT | `/api/workorders/{id}` | Actualiza una orden creada |
| PUT | `/api/workorders/{id}/status` | Cambia el estado de una orden |
| DELETE | `/api/workorders/{id}` | Elimina una orden creada o cancelada |

## Crear una orden

Ejemplo de solicitud interna proveniente del BFF:

```json
{
  "servicioId": 1,
  "descripcion": "Enchufe sin suministro",
  "direccion": "Calle de prueba 123",
  "solicitanteId": "usuario-prueba"
}
```

El servidor genera automáticamente:

- identificador de la orden;
- fecha de creación;
- estado inicial `CREADA`.

Los datos inválidos reciben:

```text
400 Bad Request
```

## Estados de una orden

El flujo implementado es:

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

No se permite avanzar hacia estados posteriores sin haber asignado previamente un técnico.

Las transiciones no permitidas generan:

```text
409 Conflict
```

## Asignación de técnico

Cuando una orden pasa a:

```text
ASIGNADA
```

debe existir un técnico asociado.

Ejemplo:

```json
{
  "status": "ASIGNADA",
  "tecnicoId": "tecnico-01",
  "repuestos": []
}
```

Si no se especifica un técnico válido, Workorders responde:

```text
400 Bad Request
```

## Repuestos asociados a una orden

Una orden puede utilizar cero o más repuestos.

Cada repuesto se identifica mediante:

```text
repuestoId
cantidad
```

Ejemplo:

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

Workorders almacena únicamente la referencia lógica del repuesto:

- identificador;
- cantidad.

El nombre, descripción y stock pertenecen al microservicio Catalog.

Los repuestos utilizados por cada orden se persisten mediante:

```text
orden_repuestos
```

No existe una relación JPA entre Workorders y Catalog, ya que ambos corresponden a microservicios independientes.

## Integración con Catalog

Cuando una orden pasa de:

```text
CREADA → ASIGNADA
```

Workorders coordina el consumo de repuestos con Catalog.

Si la orden contiene repuestos, Workorders realiza una solicitud HTTP interna hacia:

```http
POST /api/catalog/spare-parts/discount-stock
```

Ejemplo de solicitud enviada a Catalog:

```json
{
  "ordenId": 15,
  "repuestos": [
    {
      "repuestoId": 4,
      "cantidad": 2
    }
  ]
}
```

Catalog es responsable de:

- comprobar que los repuestos existan;
- comprobar que exista stock suficiente;
- descontar las cantidades solicitadas;
- evitar descuentos duplicados para una misma orden;
- validar que un reintento mantenga los mismos repuestos y cantidades.

Si una orden no requiere repuestos, Workorders puede asignarla sin realizar una solicitud de descuento a Catalog.

## Flujo de asignación

El flujo implementado es:

```text
Orden CREADA
      ↓
Workorders valida la transición
      ↓
Workorders valida el técnico
      ↓
¿La orden utiliza repuestos?
      │
      ├── No
      │     ↓
      │   continúa
      │
      └── Sí
            ↓
        Workorders llama a Catalog
            ↓
        Catalog valida y descuenta stock
            ↓
        Catalog responde correctamente
      ↓
Workorders almacena los repuestos
      ↓
Workorders cambia la orden a ASIGNADA
```

Workorders no guarda el nuevo estado ni los repuestos antes de que Catalog acepte el descuento.

## Reintentos

Si Workorders recibe nuevamente una solicitud para colocar una orden en el mismo estado en el que ya se encuentra, la operación no vuelve a modificar los repuestos ni vuelve a solicitar un descuento de stock.

Además, Catalog mantiene su propia lógica de idempotencia basada en el identificador de la orden.

Esto permite evitar descuentos duplicados ante reintentos de una misma operación.

## Manejo de errores de Catalog

Workorders traduce los principales errores de Catalog.

```text
Catalog 404
→ Workorders 404
→ alguno de los repuestos solicitados no existe
```

```text
Catalog 409
→ Workorders 409
→ stock insuficiente o conflicto con el descuento de la orden
```

```text
Catalog no disponible
→ Workorders 503
```

Si Catalog rechaza la operación, Workorders no cambia la orden a `ASIGNADA`.

## Persistencia

Workorders persiste en Oracle información relacionada con:

- órdenes de trabajo;
- servicio asociado;
- solicitante;
- técnico asignado;
- estado actual;
- fechas de creación y actualización;
- usuario que realizó la última actualización;
- repuestos asociados a la orden.

Los identificadores pertenecientes a otros microservicios, como `servicioId` y `repuestoId`, se almacenan como referencias lógicas.

## Seguridad

Workorders incluye configuración de Spring Security como OAuth2 Resource Server para validación de JWT.

La configuración utiliza Microsoft Entra ID como emisor de los tokens.

La integración de seguridad completa debe mantenerse coherente con el flujo:

```text
Frontend
→ API Gateway
→ BFF
→ Workorders
```

La configuración definitiva de propagación y validación de tokens se revisará junto con BFF, API Gateway e Infra.

## Verificación

Ejecutar:

```powershell
.\mvnw.cmd verify
```

La suite actual verifica principalmente:

- carga del contexto de Spring;
- controlador de órdenes;
- validaciones de entrada;
- respuestas HTTP;
- persistencia JPA;
- persistencia utilizando H2 durante las pruebas.

Se verificó con `mvnw verify` que:

- la incorporación del cliente de Catalog compila correctamente;
- Workorders puede utilizar el contrato de descuento de stock;
- el manejo de repuestos no rompe las pruebas existentes;
- la configuración necesaria para Catalog puede cargarse correctamente durante las pruebas.

Actualmente las pruebas existentes no realizan una integración HTTP real entre Workorders y Catalog.

La comunicación real entre ambos microservicios debe comprobarse durante la prueba integrada del sistema.

## Despliegue integrado

Workorders funciona como microservicio interno.

El flujo esperado es:

```text
Frontend
    ↓
API Gateway
    ↓
BFF
    ↓
Workorders
    ├──→ Catalog
    │       ↓
    │    Oracle RDS
    │
    ↓
Oracle RDS
```

Catalog y Workorders se comunican dentro de la infraestructura interna.

Workorders no debe publicar directamente su puerto `8082` hacia Internet.

La configuración de contenedores, direcciones internas y variables de entorno se administra desde:

```text
digitalfix-infra
```

Antes del despliegue final se deben revisar:

- credenciales Oracle;
- `CATALOG_URL`;
- direcciones internas entre microservicios;
- configuración JWT;
- BFF;
- API Gateway;
- CORS.

## Estado actual

Actualmente se encuentra implementado:

- CRUD base de órdenes;
- flujo de estados;
- asignación de técnico;
- almacenamiento de repuestos por orden;
- comunicación HTTP Workorders → Catalog;
- descuento de stock al asignar una orden;
- manejo de órdenes sin repuestos;
- prevención de descuentos repetidos por reintento;
- manejo de errores provenientes de Catalog.

La siguiente etapa corresponde a integrar este contrato con BFF, frontend e infraestructura para realizar el flujo completo.

## Refactor por capas (5 de octubre de 2026)

Los controllers utilizan DTOs independientes en `dto/request` y `dto/response`.
La capa service coordina persistencia y mapeo mediante `mapper`, sin exponer
entidades JPA en los contratos HTTP. Las validaciones, rutas, códigos, estados,
reglas de dominio y configuración existentes se conservan.
La configuración de RestClient se encuentra en `config/client`; la comunicación con Catalog sigue siendo síncrona.

La revisión y las decisiones integradas se documentan en
[REFACTOR.md del BFF](../digitalfix-ms-bff/REFACTOR.md), disponible en el workspace
con los repositorios hermanos. No se incorpora RabbitMQ ni Kafka.
