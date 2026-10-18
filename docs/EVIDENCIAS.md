# Evidencias EP3/EP4 — Paso 5

Fecha: 18 de octubre de 2026. Rama: `feature/rabbitmq-workorders`.

## Resultados ejecutados

- Referencia antes de cambios: 18 pruebas originales, 0 fallos/errores.
- Regresión tras incorporar el productor: las mismas 18 pruebas pasaron con mensajería deshabilitada.
- Suite ampliada: **33 pruebas, 0 fallos, 0 errores, 0 omitidas**. Incluye 18 originales y 15 nuevas de transacciones, publicación y consulta de Catalog.
- Integración Docker/RabbitMQ real: **2 pruebas, 0 fallos, 0 errores, 0 omitidas**; cinco comandos publicados con confirm positivo y sin retorno, lectura de los cinco en la cola colectora y retorno real detectado para routing key inexistente.

La prueba real usó el clúster existente con vhost/usuario temporal aislado. El productor no tenía permisos Configure/Read y solo Write en cmd.direct/cmd.topic. La topología del vhost digitalfix se comparó antes/después: sin cambios. No se publicaron comandos en colas funcionales ni se activaron correos. Se eliminaron solo los recursos temporales propios.

## Matriz parcial de funcionalidades

| Funcionalidad | Evidencia |
|---|---|
| CRUD, validaciones, estados y stock previos | 18 pruebas originales conservadas y aprobadas |
| Asignación y descuento síncrono | H2 + Catalog simulado; llamada de descuento verificada una vez |
| Reintentar mismo estado | Sin nueva llamada a Catalog ni publicación; comprobado también en broker real |
| Transición inválida / rechazo Catalog | Sin comandos y orden sin transición confirmada |
| Rollback | Transacción H2 marcada rollback y fallo de persistencia antes de commit: cero publicaciones |
| AFTER_COMMIT | Publicador sin llamadas dentro de transacción externa; dos comandos después del commit |
| Broker fallido tras commit | Excepción simulada, orden ASIGNADA persistida y HTTP 200; se intenta el segundo comando |
| CREADA / EN_EJECUCION / CANCELADA | Sin comandos no definidos |
| ASIGNADA / EN_DESPLAZAMIENTO / CERRADA | Cinco tipos/rutas correctos; direct y topic reales |
| UUIDs y correlación | eventId distintos, traceId compartido por transición, correlationId de la orden |
| Payloads | Correo/despacho/informe validados; repuestos y fecha de cierre contrastados con instantánea |
| Ausencia de contactos | Estados completan; solo se publica Report al cerrar; no mensajes inválidos a Notify |
| Nombre real del servicio | GET a servidor HTTP de prueba con respuesta Catalog y Bearer reenviado; fallo omite despacho |
| Actualización de contactos | Persistencia H2, propietario/gestor, rechazo de ajenos y preservación de estado/fecha/repuestos |
| Endpoint contactos | 401 sin identidad, 400 con correo inválido, 200 con gestor autenticado simulado |
| Persistencia de mensajes | delivery_mode=2 y content_type application/json leídos del broker |
| Confirms y retornos | ACK positivo sin retorno, NACK/timeout unitarios, retorno real NO_ROUTE detectado |
| Sin topología en productor | Operación real con permisos exclusivos de publicación |

## Reproducción y límites

Comandos en [MENSAJERIA.md](MENSAJERIA.md). El log completo de integración queda en `evidencias-locales/rabbit-real.log`, ignorado en Git. Los identificadores de órdenes y destinatarios usados son sintéticos. Sin RABBITMQ_TEST_VHOST, las dos pruebas reales se omiten en ejecuciones locales posteriores; el script las habilita explícitamente.

Las pruebas de transacción/persistencia utilizan H2 en modo Oracle, no Oracle real. La consulta del nombre usa un servidor HTTP de prueba que representa el contrato existente, no un Catalog desplegado. En el ciclo del broker, Catalog está simulado. Las pruebas HTTP introducen un principal JWT de pruebas, no una autenticación real Entra.

Pendiente: ejecutar migración y validar Oracle real; integrar BFF/Frontend, Compose y credenciales del productor definitivo; probar el flujo extremo a extremo con consumidores y SMTP autorizado. Nada de ello se marca como ejecutado en esta entrega.

Sin Outbox, un commit exitoso puede perder su publicación por caída del proceso. El callback no revierte Oracle ni persiste una cola de pendientes. Confirm del broker no demuestra resultado final del consumidor. Estos límites y la recuperación manual están documentados en MENSAJERIA.md.
