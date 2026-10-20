# DigitalFix — Workorders

Microservicio encargado de administrar las órdenes de trabajo de DigitalFix, controlar su ciclo de estados y coordinar el descuento de repuestos con Catalog durante la asignación de una orden.

## Evaluación 2 — Paso 5

Workorders también publica comandos RabbitMQ **después del commit de la orden**, conservando el descuento síncrono con Catalog. La mensajería se habilita explícitamente con `DIGITALFIX_RABBIT_ENABLED=true`; por defecto permanece deshabilitada para los entornos de la Evaluación 1.

Documentación de esta entrega:

- [Productor, contactos, configuración y límites](docs/MENSAJERIA.md).
- [Ejemplos JSON completos de los cinco comandos](docs/contratos.json).
- [Resultados y matriz de evidencias](docs/EVIDENCIAS.md).
- [Migración aditiva de contactos para Oracle](docs/oracle-contactos.sql).

Se exige `emailContacto` válido al crear y `emailTecnico` válido al asignar, de máximo 254 caracteres. `PATCH /api/workorders/{id}/contactos` actualiza contactos autorizadamente, sin alterar estados, fechas de transición ni stock. Las respuestas incorporan ambos campos, que pueden ser null en órdenes antiguas.

Validación de correos obligatorios: `./mvnw.cmd -B verify`, 44 pruebas aprobadas,
2 pruebas opcionales de RabbitMQ real omitidas y compilación correcta. Se usaron
H2 y dobles locales; no se modificaron datos ni restricciones de Oracle.

Las comprobaciones nuevas de transacciones se realizaron con H2 y las de publicación con RabbitMQ real aislado. La validación específica de esta versión contra Oracle real y el flujo completo desplegado sigue pendiente; las referencias posteriores al despliegue describen la base de la Evaluación 1.

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
- RestClient
- Oracle Database en Amazon RDS
- H2 para pruebas
- Docker

## Arquitectura

Workorders funciona como microservicio interno.

El flujo actual es:

```text
Angular
   ↓
AWS API Gateway
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

El frontend no se comunica directamente con Workorders.

Las solicitudes llegan desde el BFF utilizando el JWT del usuario autenticado.

## Responsabilidades

Workorders se encarga de:

- crear órdenes de trabajo;
- consultar órdenes;
- listar órdenes;
- filtrar órdenes por solicitante;
- actualizar órdenes;
- controlar el ciclo de estados;
- asignar técnicos;
- almacenar los repuestos asociados a una orden;
- coordinar el descuento de stock con Catalog;
- evitar transiciones de estado inválidas;
- persistir las órdenes en Oracle RDS.

## Puerto

El servicio utiliza:

```text
8082
```

## Endpoints

| Método | Ruta | Función |
|---|---|---|
| POST | `/api/workorders` | Crea una orden |
| GET | `/api/workorders` | Lista órdenes |
| GET | `/api/workorders/{id}` | Consulta una orden |
| PUT | `/api/workorders/{id}` | Actualiza una orden |
| PUT | `/api/workorders/{id}/status` | Cambia el estado |
| DELETE | `/api/workorders/{id}` | Elimina una orden cuando corresponde |

## Creación de órdenes

Workorders recibe desde el BFF una solicitud como:

```json
{
  "servicioId": 1,
  "descripcion": "Enchufe sin suministro",
  "direccion": "Calle de prueba 123",
  "solicitanteId": "usuario-prueba"
}
```

El `solicitanteId` es agregado por el BFF a partir del claim `oid` del JWT.

El usuario no debe ingresarlo manualmente desde el frontend.

Al crear una orden, Workorders genera automáticamente:

- identificador;
- fecha de creación;
- estado inicial `CREADA`.

## Consulta por solicitante

El endpoint:

```http
GET /api/workorders
```

puede recibir el parámetro interno:

```text
solicitanteId
```

Ejemplo:

```http
GET /api/workorders?solicitanteId=usuario-prueba
```

Si no se envía el parámetro, se devuelve la lista general.

Si se envía, se filtran las órdenes correspondientes al solicitante indicado.

El BFF decide cuándo utilizar este parámetro según el rol del usuario.

## Actualización de órdenes

La actualización utiliza:

```http
PUT /api/workorders/{id}
```

PUT y DELETE obtienen el actor desde el `oid` del JWT autenticado y validan su rol.
No usan `solicitanteId`, `admin` ni roles proporcionados en parámetros/cuerpo para
autorizar. Admin puede operar sobre órdenes ajenas; Cliente y Operador solo sobre
las propias. El propietario no cambia y la auditoría registra al actor real.
Las restricciones de estado se aplican a todos, incluido Admin.

La edición de correo reutiliza PATCH `/contactos`, independiente de PUT: omitir
conserva el contacto; un null explícito se rechaza. No se permite vaciar el correo
del cliente ni el del técnico asignado. PUT no exige correo. PATCH no publica notificaciones ni modifica
estado, stock o fecha de transición. El frontend informa guardado parcial si PUT
termina y PATCH falla; no se promete atomicidad entre ambas llamadas.

Validación del ajuste (19/10/2026): `./mvnw.cmd -B verify`, 41 aprobadas, 0 fallidas
y 2 pruebas opcionales de RabbitMQ real omitidas. Las nuevas pruebas usan H2 y un
principal JWT local en MVC; no verifican Entra/Oracle reales. Integrar Workorders
antes de BFF y Frontend; los parámetros antiguos pueden recibirse pero se ignoran
en PUT/DELETE y nunca sustituyen la identidad autenticada.

Workorders permite editar únicamente órdenes que todavía se encuentren en estado:

```text
CREADA
```

Si se intenta modificar una orden en otro estado, responde:

```text
409 Conflict
```

## Estados

Los estados implementados son:

```text
CREADA
ASIGNADA
EN_DESPLAZAMIENTO
EN_EJECUCION
CERRADA
CANCELADA
```

El flujo normal es:

```text
CREADA
→ ASIGNADA
→ EN_DESPLAZAMIENTO
→ EN_EJECUCION
→ CERRADA
```

Una orden activa también puede pasar a:

```text
CANCELADA
```

Una orden `CERRADA` o `CANCELADA` no puede continuar hacia otro estado.

## Reglas de transición

Las principales transiciones permitidas son:

```text
CREADA
→ ASIGNADA

ASIGNADA
→ EN_DESPLAZAMIENTO

EN_DESPLAZAMIENTO
→ EN_EJECUCION

EN_EJECUCION
→ CERRADA
```

Además, una orden que no esté cerrada ni cancelada puede pasar a:

```text
CANCELADA
```

Si la transición no es válida, Workorders responde:

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

Si no existe un técnico válido, Workorders responde:

```text
400 Bad Request
```

El técnico se define durante la asignación.

## Repuestos asociados a una orden

Durante la asignación se pueden utilizar cero o más repuestos.

Cada repuesto contiene:

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
    }
  ]
}
```

Workorders almacena únicamente:

- identificador lógico del repuesto;
- cantidad utilizada.

La información propia del repuesto, como nombre, descripción y stock, pertenece a Catalog.

## Persistencia de repuestos

Los repuestos asociados a una orden se almacenan mediante la estructura:

```text
orden_repuestos
```

No existe una relación JPA directa entre las entidades de Workorders y Catalog.

Ambos corresponden a microservicios separados.

## Integración con Catalog

Cuando una orden cambia de:

```text
CREADA
→ ASIGNADA
```

y utiliza repuestos, Workorders llama internamente a Catalog.

Endpoint:

```http
POST /api/catalog/spare-parts/discount-stock
```

Ejemplo:

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

El flujo es:

```text
Workorders
   ↓
Catalog
   ↓
validación de repuestos
   ↓
validación de stock
   ↓
descuento de stock
   ↓
Oracle RDS
```

Si la orden no utiliza repuestos, Workorders puede asignarla sin llamar a Catalog.

## Flujo de asignación

El proceso implementado es:

```text
Orden CREADA
      ↓
Workorders valida la transición
      ↓
Workorders valida el técnico
      ↓
¿Utiliza repuestos?
      │
      ├── No
      │     ↓
      │   continúa
      │
      └── Sí
            ↓
        llama a Catalog
            ↓
        Catalog valida stock
            ↓
        Catalog descuenta stock
            ↓
        Catalog responde correctamente
      ↓
Workorders almacena los repuestos
      ↓
Workorders cambia la orden a ASIGNADA
```

Workorders no confirma la asignación con repuestos si Catalog rechaza previamente el descuento.

## Reintentos

Si se solicita nuevamente exactamente el mismo estado actual de una orden:

```text
estado actual = estado solicitado
```

Workorders devuelve la orden sin volver a modificar los repuestos ni volver a solicitar un descuento de stock.

Además, Catalog posee su propia lógica de idempotencia para evitar descuentos duplicados.

## Errores provenientes de Catalog

Workorders traduce los principales errores de Catalog.

### Repuesto inexistente

```text
Catalog 404
→ Workorders 404
```

### Stock insuficiente o conflicto

```text
Catalog 409
→ Workorders 409
```

### Catalog no disponible

```text
Catalog no responde
→ Workorders 503
```

Si Catalog rechaza la operación, Workorders no finaliza el cambio a `ASIGNADA`.

## Eliminación de órdenes

La eliminación utiliza:

```http
DELETE /api/workorders/{id}
```

Solo se pueden eliminar órdenes en estado:

```text
CREADA
```

o:

```text
CANCELADA
```

Si la orden está en otro estado, se responde:

```text
409 Conflict
```

## Persistencia

Workorders utiliza Oracle Database en Amazon RDS.

Persiste información relacionada con:

- identificador de la orden;
- servicio asociado;
- descripción;
- dirección;
- solicitante;
- técnico;
- estado;
- fecha de creación;
- fecha de actualización;
- usuario que realizó la última actualización;
- repuestos asociados.

Los identificadores como:

```text
servicioId
repuestoId
```

son referencias lógicas hacia información perteneciente a otros microservicios.

## Configuración de Oracle

La conexión utiliza variables de entorno:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
```

La URL debe tener formato JDBC compatible con Oracle.

Ejemplo conceptual:

```text
jdbc:oracle:thin:@//HOST:1521/SERVICIO
```

Las credenciales reales no deben almacenarse en Git.

## Hibernate

Durante esta etapa se utiliza:

```properties
spring.jpa.hibernate.ddl-auto=update
```

Hibernate actualiza el esquema utilizado por el microservicio durante el desarrollo.

## Comunicación con Catalog

La URL de Catalog se configura mediante:

```text
CATALOG_URL
```

La propiedad utilizada es:

```properties
catalog.url=${CATALOG_URL:http://catalog:8081}
```

En Docker se utiliza:

```text
http://catalog:8081
```

En una ejecución local puede configurarse:

```text
http://localhost:8081
```

## Seguridad

Workorders está configurado como OAuth2 Resource Server.

Utiliza Microsoft Entra ID como emisor de los JWT y valida la audience correspondiente a DigitalFix API.

El BFF reenvía el Bearer token recibido desde el frontend.

Flujo:

```text
Frontend
→ API Gateway
→ BFF
→ Workorders
```

El cambio de estado también considera los roles:

```text
Admin
Operador
```

como roles con capacidad de gestión de órdenes.

Las validaciones de acceso generales también se aplican previamente en el BFF.

## Configuración principal

El archivo:

```text
src/main/resources/application.properties
```

configura:

- puerto `8082`;
- Oracle RDS;
- Hibernate;
- issuer JWT;
- audience JWT;
- URL interna de Catalog.

## Ejecución local

Configurar primero las variables de entorno de Oracle.

Después ejecutar:

```powershell
.\mvnw.cmd spring-boot:run
```

El servicio queda disponible en:

```text
http://localhost:8082
```

Para probar operaciones que utilicen repuestos, Catalog también debe encontrarse disponible.

## Pruebas

Ejecutar:

```powershell
.\mvnw.cmd verify
```

La suite cubre principalmente:

- carga del contexto de Spring;
- controlador de órdenes;
- validaciones de entrada;
- respuestas HTTP;
- persistencia JPA;
- ejecución con H2 durante las pruebas.

Las pruebas automatizadas no sustituyen la comprobación integrada real entre:

```text
BFF
→ Workorders
→ Catalog
→ Oracle RDS
```

Ese flujo también se verifica mediante pruebas funcionales del sistema desplegado.

## Docker

Workorders se construye utilizando su:

```text
Dockerfile
```

La ejecución conjunta del backend se administra desde:

```text
digitalfix-infra/docker-compose.yml
```

El Compose central ejecuta:

```text
BFF
Catalog
Workorders
```

Workorders utiliza el nombre DNS interno:

```text
catalog
```

para comunicarse con Catalog dentro de la red Docker.

El puerto `8082` no se publica directamente hacia Internet.

## Despliegue actual

Actualmente Workorders se ejecuta en contenedor dentro de la instancia EC2 junto con BFF y Catalog.

El flujo operativo es:

```text
Angular
   ↓
API Gateway
   ↓
BFF en EC2
   ↓
Workorders en EC2
   ├──→ Catalog en EC2
   │       ↓
   │    Oracle RDS
   │
   ↓
Oracle RDS
```

La comunicación entre los microservicios se realiza dentro de la red Docker.

## Manejo de errores

Los principales códigos utilizados son:

```text
400 Bad Request
404 Not Found
409 Conflict
503 Service Unavailable
```

Se utilizan para representar:

- solicitudes inválidas;
- órdenes inexistentes;
- repuestos inexistentes;
- transiciones no permitidas;
- stock insuficiente;
- conflictos de estado;
- indisponibilidad de Catalog.

## Estructura principal

El proyecto se organiza por responsabilidades:

```text
controller
service
repository
entity
dto
mapper
client
config
```

Los controllers exponen los endpoints HTTP.

Los services contienen las reglas de negocio.

Los repositories administran la persistencia.

Los clients permiten la comunicación con Catalog.

Los DTOs representan solicitudes y respuestas.

Los mappers separan las entidades JPA de los contratos HTTP.

## Estado actual

Actualmente se encuentran operativos:

- creación de órdenes;
- consulta de órdenes;
- listado global;
- listado por solicitante;
- actualización de órdenes creadas;
- eliminación de órdenes creadas o canceladas;
- flujo completo de estados;
- cancelación de órdenes;
- asignación de técnico;
- asociación de repuestos;
- comunicación Workorders → Catalog;
- descuento real de stock;
- prevención de descuentos duplicados;
- manejo de errores de Catalog;
- persistencia real en Oracle RDS;
- integración con BFF;
- integración con el despliegue Docker en EC2.

El flujo completo con frontend, API Gateway, BFF, Catalog y Oracle RDS se encuentra integrado y fue probado funcionalmente.

## Documentación técnica

Los archivos de refactor existentes conservan información histórica sobre reorganización por capas, convenciones técnicas y uso de Lombok.

El `README.md` representa el estado funcional actual del microservicio.

## Flujo de trabajo

Los cambios se desarrollan en ramas independientes y se integran a `main` mediante Pull Request.

La rama `main` representa la versión estable utilizada para la entrega.
