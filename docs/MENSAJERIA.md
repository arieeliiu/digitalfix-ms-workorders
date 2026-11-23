# Paso 5 — Productor RabbitMQ

## Arquitectura y transacciones

`OrdenService` conserva las transiciones, el bloqueo pesimista de la orden, las reglas de autorización y el descuento síncrono de Catalog. Al cambiar realmente el estado prepara un `OrdenResponse` con repuestos ya materializados. `NotificacionesOrden` transforma esos valores en records inmutables, valida los payloads y registra un `TransactionSynchronization.afterCommit`.

Si la transacción hace rollback, el callback no publica. Si confirma, cada comando se procesa independientemente. Una excepción de publicación se registra y no sale del callback: no revierte la orden ni convierte el resultado HTTP en un fallo engañoso. No se accede a entidades JPA ni colecciones lazy después del commit.

La consulta del nombre del servicio usa el GET real `/api/catalog/services`, busca por ID y reenvía el Bearer JWT actual cuando existe. Se hace durante la preparación de la instantánea, con timeout de conexión de 3 s y lectura de 5 s. Su fallo omite solo el despacho. No se inventan nombres ni se modifica Catalog. El descuento de stock sigue usando la integración síncrona existente, con esos timeouts acotados; sus errores siguen abortando la asignación.

Un rollback de Workorders después de descontar stock no revierte una transacción independiente de Catalog. Esa limitación preexistente entre servicios se conserva y Catalog debe mantener su idempotencia por orden; esta entrega no añade una transacción distribuida.

## Comandos por transición

| Estado destino | Tipo | Exchange | Routing key |
|---|---|---|---|
| ASIGNADA | ORDEN_ASIGNADA | cmd.direct | email.send |
| ASIGNADA | DESPACHO_TECNICO | cmd.direct | dispatch.ticket |
| EN_DESPLAZAMIENTO | TECNICO_EN_CAMINO | cmd.topic | email.en_camino |
| CERRADA | ORDEN_CERRADA | cmd.direct | email.send |
| CERRADA | GENERAR_REPORTE | cmd.direct | report.gen |

Creación, CREADA, EN_EJECUCION y CANCELADA no generan comandos. Repetir el estado actual no descuenta stock, modifica contactos ni publica. Actualizar contactos tampoco publica. Cada comando usa una sola ruta.

## Contratos

[contratos.json](contratos.json) incluye los cinco sobres completos de ejemplo, con datos sintéticos. `schemaVersion=1.0`, UUID distinto por eventId, UUID traceId compartido únicamente por comandos de una transición, timestamp UTC y correlationId decimal de la orden. Nunca se serializan entidades, JWT ni credenciales.

- Correo: exclusivamente ordenId y emailContacto. solicitanteId nunca es un destinatario.
- Despacho: ordenId, emailTecnico, tecnicoId, servicioId, servicioNombre real y direccion.
- Informe: ordenId, estado CERRADA, solicitanteId, tecnicoId, servicioId, descripcion, direccion, fechaCreacion, fechaCierre y repuestos con repuestoId/cantidad. fechaCierre copia fechaActualizacion registrada al cerrar, no la fecha de publicación. Lista vacía admitida.

Las validaciones reproducen las de Notify/Report: correos no vacíos hasta 254 caracteres, IDs positivos, identificadores personales hasta 100, nombre del servicio hasta 200, dirección hasta 300, descripción hasta 1000, fechas válidas y hasta 1000 repuestos para Report. El publicador aplica límites de 64 KiB para Notify y 256 KiB para Report. Un dato insuficiente no produce un mensaje inválido.

## Contactos y compatibilidad HTTP

Las columnas nullable `email_contacto` y `email_tecnico`, de hasta 254 caracteres, no reemplazan `solicitante_id` ni `tecnico_id`. Las órdenes antiguas siguen siendo válidas. El mapeo y las respuestas incluyen ambos campos.

Creación, manteniendo todos los campos anteriores:

```json
{"servicioId":2,"descripcion":"Revisión eléctrica","direccion":"Ñuñoa","solicitanteId":"oid-derivado-por-bff","emailContacto":"cliente@example.org"}
```

Asignación:

```json
{"status":"ASIGNADA","tecnicoId":"tecnico-01","repuestos":[{"repuestoId":4,"cantidad":2}],"emailTecnico":"tecnico@example.org"}
```

Crear exige emailContacto válido y la transición CREADA → ASIGNADA exige emailTecnico válido, ambos de máximo 254 caracteres. Omitirlos, enviar null, vacío, espacios, formato inválido o longitud excesiva devuelve 400 en esas operaciones. La asignación valida el correo antes del descuento de stock. Un reintento del mismo estado no modifica la orden ni vuelve a exigir el correo. Las órdenes antiguas siguen consultables y PUT de datos generales no exige correo.

Para órdenes antiguas, incluso cerradas:

```http
PATCH /api/workorders/15/contactos
Authorization: Bearer <JWT>
Content-Type: application/json

{"emailContacto":"cliente@example.org","emailTecnico":"tecnico@example.org"}
```

El actor de este endpoint procede del claim **oid del JWT**, no de un query parameter. Sin autenticación responde 401; sin oid, 403. El propietario puede modificar únicamente emailContacto. Operador/Admin puede modificar ambos contactos de órdenes gestionadas. Un cliente ajeno recibe 404; un propietario que intenta modificar emailTecnico recibe 403. Una propiedad omitida conserva el valor, incluso si es null en una orden antigua. Un null explícito se rechaza. emailContacto no puede vaciarse; emailTecnico solo puede vaciarse si la orden no tiene técnico asignado. No cambia estado, repuestos, fecha de creación, fecha de transición ni usuario de la última transición.

Las rutas anteriores mantienen su modelo de confianza con el BFF y el JWT. No se han alterado BFF, Entra ni API Gateway. Para Paso 6, BFF debe trasladar estos campos, conservarlos en sus DTO de respuesta y añadir un proxy autorizado para contactos, reenviando el JWT; Frontend deberá capturar correo del cliente y del técnico sin sustituir sus identificadores de autenticación.

La migración [oracle-contactos.sql](oracle-contactos.sql) añade solo columnas ausentes, nullable. No fue ejecutada contra Oracle en esta tarea. El `ddl-auto=update` actual también puede crearlas en desarrollo. En despliegues controlados, revisar/ejecutar la migración con el propietario del esquema antes de usar `ddl-auto=validate`.

## Confirmaciones, errores y recuperación

`RabbitTemplate` usa mandatory=true, mensajes persistentes, content_type application/json y confirms correlacionados. Se espera el confirm con límite configurable (5 s por defecto). Un retorno sin ruta se comprueba antes de considerar exitoso el confirm: ACK del broker más retorno **no** es publicación satisfactoria. NACK, timeout, falta de conexión y excepciones se registran como fallidos. No hay retry automático del publicador.

Los logs separan `resultado=publicado`, `resultado=omitido` y `resultado=fallido`, con tipo, eventId, traceId, ordenId y motivo; no contienen payloads ni correos. Omisiones por contactos/nombre insuficientes se registran después del commit. Cada comando se intenta de forma independiente: un correo fallido no impide intentar el informe.

Un confirm positivo acredita aceptación por RabbitMQ, **no** ACK del consumidor, entrega SMTP ni PDF final. Un timeout/desconexión puede dejar resultado incierto. La espera está en el hilo de la solicitud después del commit y añade latencia acotada por comando (más tiempos de conexión/transporte); hasta dos comandos por transición.

**Sin Transactional Outbox hay una ventana de pérdida entre commit y publicación.** También hay pérdida si el proceso muere durante el callback. No se persiste un backlog ni el sobre completo, y no se promete exactamente una vez. Actualizar contactos no recupera automáticamente comandos omitidos; repetir estado tampoco.

Recuperación operativa pendiente de integración: identificar el comando por logs, corregir contactos o disponibilidad de Catalog, revisar broker/DLQ/archivos y preparar una publicación individual autorizada con datos verificados. Si se conserva el sobre original, reutilizarlo exactamente (incluido eventId). No reconstruir un contenido distinto usando ese mismo eventId. Si se perdió el sobre, investigar el resultado previo antes de decidir un nuevo comando/eventId: un envío incierto puede haber sido procesado. Para comandos omitidos nunca publicados, reconstruir desde datos revisados y usar un nuevo eventId. No mover estados hacia atrás ni repetir descuentos para provocar notificaciones.

## Configuración del Paso 6

`.env.example` contiene nombres y ejemplos sin credenciales. Un archivo .env por sí solo no es cargado automáticamente por Java; usar variables de entorno o env_file/environment en Compose.

| Variable | Valor |
|---|---|
| DIGITALFIX_RABBIT_ENABLED | false por defecto; **true explícito en Evaluación 2** |
| RABBITMQ_ADDRESSES | rabbitmq1:5672,rabbitmq2:5672 |
| RABBITMQ_VHOST | digitalfix |
| RABBITMQ_USERNAME / PASSWORD | Usuario propio y contraseña |
| RABBITMQ_CONFIRM_TIMEOUT | 5s por defecto, máximo 30s |
| CATALOG_URL | URL interna existente de Catalog |

Confirms correlacionados, returns habilitados y mandatory=true son parte de la configuración obligatoria, no se deshabilitan para resolver fallos. `spring.rabbitmq.dynamic=false` impide administración automática de topología. No hay queues, exchanges, DLQ ni listeners declarados por Workorders.

Crear `digitalfix_workorders` sin etiquetas administrativas, permisos únicamente en vhost digitalfix: Configure `^$`, Read `^$`, Write `^cmd\.(direct|topic)$`. No usar el administrador inicial ni cuentas de Notify/Report. La prueba real usa una cuenta equivalente pero temporal y un vhost aislado; no crea la cuenta definitiva de despliegue.

En el Compose central, después de revisar el Paso 6, añadir la red externa `digitalfix-messaging` a Workorders **conservando su red actual de Catalog**, e inyectar las variables anteriores. No publicar puertos AMQP/HTTP nuevos. Notify/Report deben haber declarado la topología compatible. No se modificó el Compose de Infra aquí. Dos direcciones dan posibilidad de reconexión; no convierten colas classic en alta disponibilidad.

## Pruebas reproducibles

```powershell
.\mvnw.cmd -B test
docker build --target pruebas -t digitalfix-workorders:pruebas .
python scripts/verificar_rabbit.py
```

La prueba real usa el clúster del Paso 1 pero crea un vhost/usuario únicos, exchanges direct/topic y una cola colectora. El productor solo tiene Write sobre los exchanges. Un observador exclusivo de pruebas verifica los cinco comandos, persistencia y ausencia de duplicados. Se prueba además un retorno real sin ruta. Al terminar se eliminan exclusivamente el vhost y usuario temporales.

El script lee credenciales de Infra en memoria, pasa un archivo de entorno local ignorado al contenedor y lo elimina al terminar. No imprime secretos ni consume colas funcionales. Catalog se simula en esa prueba; Oracle se sustituye por H2. La consulta HTTP de nombre se prueba por separado con servidor local y reenvío de Bearer. Los JWT de las pruebas HTTP representan autenticación simulada, no validación real con Entra.

## Eventos Kafka (workorders.events)

Además de los comandos RabbitMQ, Workorders publica **eventos**: hechos ya ocurridos que consume Audit (grupo `audit`). Report conserva sus comandos RabbitMQ; no existe un consumidor Kafka de Report. El clúster y los tópicos están en `digitalfix-infra/kafka`.

| Elemento | Valor |
|---|---|
| Tópico | `workorders.events` (3 particiones, RF 3, `delete`, 7 días) |
| Key | `ordenId` como texto: todos los eventos de una orden van a la misma partición y se leen en orden |
| Value | `Sobre` en JSON (mismo formato que RabbitMQ), `StringSerializer` |
| Header | `correlationId` = `ordenId` |
| Confirmación | `acks=all`; idempotencia del productor activa por defecto |

**Cuándo se publica:** cada registro de `historial_ordenes` produce un evento, en `afterCommit` de la misma transacción (`HistorialOrdenService.registrar` → `CoordinadorEventosOrden` → `PublicadorKafka`). Un rollback no publica. Repetir el estado actual o una transición rechazada no registra historial y, por lo tanto, no publica.

| `type` | Cuándo | `estadoAnterior` → `estadoNuevo` |
|---|---|---|
| `ORDEN_CREADA` | Al crear | `null` → `CREADA` |
| `ORDEN_ESTADO_CAMBIADO` | Cada transición real | anterior → nuevo |
| `ORDEN_ELIMINADA` | Al eliminar (CREADA o CANCELADA) | estado final → `null` |

Las ediciones reales se publican únicamente en `audit.timeline` (3 particiones,
RF 3, `delete`, 30 días), con el mismo sobre/payload 1.0 y key `ordenId`:

| `type` | Acción | Operación |
|---|---|---|
| `ORDEN_DATOS_ACTUALIZADOS` | `EDICION_DATOS` | PUT de datos de la orden |
| `ORDEN_CONTACTOS_ACTUALIZADOS` | `EDICION_CONTACTOS` | PATCH de contactos |
| `ORDEN_REPUESTOS_ACTUALIZADOS` | `EDICION_REPUESTOS` | PUT de repuestos |

En una edición los estados anterior/nuevo son iguales. Se compara el estado
normalizado antes de guardar: no-op, reintentos ya aplicados y rollback no producen
eventos. Los contactos/repuestos de una asignación forman parte de la transición,
sin eventos de edición adicionales. No se envían valores de correos ni otros datos
personales. El actor y roles de HTTP se obtienen del JWT; no se usa el query param
como respaldo de un `oid` ausente, tampoco al cambiar estado o crear la orden.

**Orden de activación:** Infra PR #17, lector Audit compatible y finalmente este
productor. El lector anterior rechaza los tipos nuevos. El historial Oracle requiere
ampliar su check de acciones: para instalación nueva se actualizó `oracle-historial.sql`;
para una existente se preparó `oracle-historial-ediciones.sql`. Revisar todos los checks
(incluidos los generados por Hibernate) y la definición de un V2 ya existente antes de
ejecutar. Los scripts no se aplicaron ni se verificaron contra Oracle en esta tarea.

Prueba optativa contra **Kafka local aislado**, con H2 real y Catalog simulado:

```powershell
.\mvnw.cmd -B '-Dtest=KafkaEdicionesRealTests' '-Ddigitalfix.pruebas.kafka=true' test
```

Produce datos sintéticos en ambos tópicos del clúster indicado; no ejecutarla contra
datos de negocio. Se comprobó creación/transición y las tres ediciones, orden por key,
ausencia de eventos duplicados por no-op y ausencia de emisión ante rollback.
No certifica Oracle, JWT de Entra ni el stock del Catalog real.

**Payload `EventoOrden`:** `ordenId`, `accion` (según las tablas anteriores), `estadoAnterior`, `estadoNuevo`, `tecnicoId`, `actorId` (oid de Entra), `actorRoles` y `fecha`. No incluye correos ni descripciones.

```json
{"schemaVersion":"1.0","type":"ORDEN_ESTADO_CAMBIADO","eventId":"bc8d4a43-...","timestamp":"2026-11-23T03:42:49.230956900Z",
 "traceId":"4fb63c3f-...","correlationId":"22",
 "payload":{"ordenId":22,"accion":"CAMBIO_ESTADO","estadoAnterior":"CREADA","estadoNuevo":"ASIGNADA",
            "tecnicoId":"tec-003","actorId":"<oid>","actorRoles":"Admin","fecha":"2026-11-23T03:42:49.230956900Z"}}
```

**Fallos:** si Kafka no confirma en `KAFKA_TIMEOUT` (5 s), se registra `evento resultado=fallido ... motivo=...` y la orden sigue confirmada; el evento se pierde (no hay Outbox). `kafka_timeout_resultado_incierto` significa que el broker pudo haberlo recibido.

**Para los consumidores:** la entrega es *at-least-once*: deduplicar por `eventId` (tabla de mensajes procesados). Cada consumidor publica sus fallos definitivos en su propio DLT (`workorders.events.audit.DLT`, `workorders.events.report.DLT`). Cambiar `EventoOrden` exige actualizar antes a los consumidores.

**Configuración:** `DIGITALFIX_KAFKA_ENABLED` (false por defecto), `KAFKA_BOOTSTRAP_SERVERS` (local: `localhost:9092,localhost:9093,localhost:9094`) y `KAFKA_TIMEOUT`. Verificado el 23/11/2026 contra el clúster local: la orden 22 publicó `ORDEN_CREADA` y `ORDEN_ESTADO_CAMBIADO` en la partición 0, offsets 0 y 1.
