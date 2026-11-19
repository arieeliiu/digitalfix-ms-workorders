# digitalfix-ms-workorders

Contexto global (arquitectura, roles, reglas de git y Docker) en `../CLAUDE.md`. Aquí va solo lo propio de este servicio.

Órdenes de trabajo: CRUD, transiciones de estado, coordinación de stock con Catalog y productor RabbitMQ.
Puerto 8082, Oracle (tablas `ordenes_trabajo`, `orden_repuestos`). Es interno: solo lo llama el BFF.

Clase principal: `WorkordersApplication` (renombrada desde `DigitalfixMsWorkordersApplication`).

## Mapa del código (`cl.digitalfix.workorders`)

| Paquete | Clave |
|---|---|
| `controller/OrdenController` | `/api/workorders`. Extrae el actor (`oid`) y los roles del JWT |
| `service/OrdenService` | Toda la lógica. Clase `@Transactional(readOnly = true)` y mutaciones con `@Transactional` |
| `entity/EstadoOrden` | `permite(siguiente)`: única fuente de verdad de las transiciones |
| `entity/OrdenTrabajo`, `RepuestoOrden` | Entidad con `@Getter` y métodos de dominio (`cambiarEstado`, `actualizarRepuestos`, `actualizarContactos`). Sin setters |
| `repository/OrdenRepository` | `findByIdParaModificar` con `PESSIMISTIC_WRITE`: úsalo en toda mutación |
| `service/HistorialOrdenService`, `entity/HistorialOrden`, `AccionHistorial` | Auditoría en `historial_ordenes`: `CREACION`, `CAMBIO_ESTADO` y `ELIMINACION`. Registros inmutables, sin FK a la orden. `GET /{id}/historial` (Admin y Auditor) |
| `client/CatalogoClient` | `descontarStock`, `ajustarStock` y `nombreServicio`. Traduce errores de Catalog a 404, 409 o 503 |
| `messaging/` | `NotificacionesOrden` (arma comandos y publica en `afterCommit`), `PublicadorRabbit` (confirms y mandatory), `ContratosMensajeria` (records del contrato) |
| `config/` | `RabbitPublisherConfig` (`mandatory=true`) y `client/RestClientConfig` (RestClient con qualifier `catalogRestClient`) |

## Seguridad y modelo de confianza

- Resource server de Entra con la autoconfiguración de Spring (no hay `SecurityConfig`): todo exige un JWT válido (issuer y audience en `application.properties`).
- `PUT /{id}`, `DELETE /{id}`, `PUT /{id}/repuestos` y `PATCH /{id}/contactos` toman el actor del claim `oid`.
- `GET /api/workorders?solicitanteId=` y `PUT /{id}/status?solicitanteId=` reciben el actor por **query param**, puesto por el BFF a partir del `oid`. Es una inconsistencia heredada: no la "corrijas" sin coordinar el cambio con el BFF (`OrdenClient.cambiarEstado`), y nunca expongas Workorders fuera de la red interna.
- Admin y Operador gestionan cualquier orden. Cliente solo las suyas: una orden ajena responde **404** (no 403) para no revelar que existe.

## Reglas de dominio implementadas

- `cambiarEstado`: si `actual == siguiente` responde sin efectos (no descuenta stock, no cambia repuestos, no publica).
- ASIGNADA exige `emailTecnico` válido **antes** de descontar stock y un `tecnicoId`. El técnico no se cambia en otros estados.
- El stock se descuenta solo si hay repuestos (`POST /api/catalog/spare-parts/discount-stock`, idempotente por orden en Catalog).
- `actualizarRepuestos` (solo en ASIGNADA, EN_DESPLAZAMIENTO o EN_EJECUCION) envía `anteriores` y `nuevos` a `adjust-order-stock`. Si los `anteriores` no coinciden responde 409 y un reintento con lo ya guardado no hace nada.
- Editar datos generales: CREADA por el dueño (o Admin) y estados activos solo un gestor. Eliminar: solo CREADA o CANCELADA.
- `emailContacto` es obligatorio al crear (validado con `exigirCorreo`). Máximo 254 caracteres.
- Un rollback de Workorders **no** revierte un descuento ya hecho en Catalog: es una limitación conocida, no una transacción distribuida.

## Historial de auditoría (no romper)

- Se escribe en la **misma transacción** que el cambio (`Propagation.MANDATORY` en `HistorialOrdenService`): llamarlo fuera de una transacción falla a propósito.
- Solo transiciones reales: el reintento del mismo estado y las transiciones rechazadas no registran nada. Toda mutación nueva que deba auditarse se registra **después** de sus validaciones.
- El actor del historial es el `oid` del JWT (`ActorAuditoria`), no el query param `solicitanteId`; el param se sigue usando solo para la autorización heredada.
- Sin FK a `ordenes_trabajo`: el historial sobrevive a la eliminación. Nunca lo borres en cascada ni lo actualices.
- No guarda correos ni descripciones. Ediciones de datos, repuestos y contactos **no** se registran todavía.
- Pendiente: proxy en el BFF y rol `Auditor` en Entra y frontend. A futuro, publicar estos mismos registros a Kafka (`workorders.events`) para `ms-audit`.
- Migración manual: `docs/oracle-historial.sql`. Tests en `HistorialOrdenTests`.

## Mensajería (leer `docs/MENSAJERIA.md` antes de tocarla)

- `NotificacionesOrden.despuesDelCommit(instantanea)` se llama solo desde `cambiarEstado`, después de `save`. Prepara records inmutables dentro de la transacción y publica en `afterCommit`.
- Publica en ASIGNADA (correo y despacho), EN_DESPLAZAMIENTO (correo por topic) y CERRADA (correo e informe). Las routing keys están en la tabla de `docs/MENSAJERIA.md`.
- Un fallo al publicar se loguea (`resultado=fallido`) y **nunca** se propaga como error HTTP ni revierte la orden.
- `spring.rabbitmq.dynamic=false`: Workorders no declara exchanges ni colas. El usuario `digitalfix_workorders` solo tiene Write sobre `cmd.direct` y `cmd.topic`.
- Cambiar un payload exige cambiar también `dto/Contrato` en Notify o Report y `docs/contratos.json`. **Report rechaza propiedades desconocidas** (`FAIL_ON_UNKNOWN_PROPERTIES`): agregar un campo a `Informe` sin actualizar Report primero manda los mensajes a la DLQ.
- `digitalfix.rabbit.enabled=false` por defecto. En la evaluación 2 se activa con `DIGITALFIX_RABBIT_ENABLED=true`.
- Próximo paso según el caso: publicar eventos a Kafka (`workorders.events`). Hazlo como adaptador nuevo (`messaging/kafka`) detrás de una interfaz, sin mezclarlo con `PublicadorRabbit`, y evalúa un Outbox.

## Variables de entorno

**Credenciales locales:** `src/main/resources/application.properties` tiene, sin commitear, el endpoint, el usuario y la contraseña de la RDS como valores por defecto de `${DB_URL:...}`, `${DB_USERNAME:...}` y `${DB_PASSWORD:...}`. No los commitees (`git add -p` y omite esas líneas), no los copies a otros archivos y no muestres la contraseña. En Docker los reemplaza `digitalfix-infra/apps/.env`.

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `CATALOG_URL` (por defecto `http://localhost:8081`; en Docker lo define el Compose), `DIGITALFIX_RABBIT_ENABLED`,
`RABBITMQ_ADDRESSES`, `RABBITMQ_VHOST`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` y `RABBITMQ_CONFIRM_TIMEOUT` (1 ms a 30 s). Ver `.env.example`.

Al correrlo local contra la RDS no hace falta ninguna variable. Con `DIGITALFIX_RABBIT_ENABLED` sin definir no publica comandos, y eso es lo esperado en local.

## Tests

```powershell
.\mvnw.cmd -B test                                   # H2 en modo Oracle y Catalog simulado
docker build --target pruebas -t digitalfix-workorders:pruebas .
python scripts/verificar_rabbit.py                    # Rabbit real: vhost y usuario temporales, se borran al final
```

- Suites: `OrdenServiceTests`, `OrdenControllerTests`, `EdicionAdminTests`, `MensajeriaTransaccionesTests`, `PublicadorRabbitTests`, `CatalogoNombreTests`, `PersistenciaReinicioTests` y `RabbitRealTests` (se salta sin broker).
- Toda regla nueva de estado o permiso necesita su test en `OrdenServiceTests` o `EdicionAdminTests`. Todo cambio de mensajería, en `MensajeriaTransaccionesTests` (rollback = sin publicación).
- Migraciones de esquema: SQL en `docs/` (ver `oracle-contactos.sql`). Nunca ejecutarlas contra Oracle real sin confirmación.
