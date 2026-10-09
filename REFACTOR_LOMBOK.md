# DigitalFix workorders: refactor no funcional con Lombok

9 de octubre de 2026. Alcance: tipos Java, código repetitivo y documentación.
La referencia funcional es el checkout inicial, incluidos los cambios locales
previos. No se cambiaron ramas ni se crearon commits durante esta tarea.

## Responsabilidades y decisiones

Workorders mantiene Controller → Service → Repository → JPA/Oracle y el
client HTTP síncrono a Catalog. `OrdenController`, `OrdenService`,
`OrdenRepository`, `CatalogoClient`, `OrdenMapper` y `RestClientConfig` reemplazan
los nombres técnicos anteriores. Los requests terminan en Request y las
respuestas de órdenes en OrdenResponse; los componentes JSON se conservan.

Se usa @RequiredArgsConstructor en controller/service. OrdenTrabajo usa
@Getter y @NoArgsConstructor(access = PROTECTED), sin setters nuevos. Se conserva
su constructor de negocio de cuatro argumentos y todos sus métodos de cambios,
fechas y ciclo JPA. RepuestoOrden usa @Getter, @NoArgsConstructor(PROTECTED) y
@AllArgsConstructor para generar exactamente los dos constructores previos.
No se generan equals/hashCode/toString ni se utiliza @Data.

EstadoOrden, el String persistido, las transiciones, la propiedad en edición y
borrado, los permisos de gestión, la idempotencia de asignación, los bloqueos y
los límites transaccionales permanecen iguales. No se cambia ninguna consulta
JPQL ni el nombre JPA de OrdenTrabajo. Los mappers permanecen simples.

## Nomenclatura y archivos renombrados

Antes de editar se registraron declaraciones, imports, constructores, referencias,
generics, tests, mappers y dependencias de los tres servicios. Los renombrados
actuaron sobre identificadores Java; literals y nombres externos se conservaron.

| Tipo anterior | Tipo actual | Archivo actual |
|---|---|---|
| OrdenTrabajoControlador | OrdenController | `src/main/java/cl/digitalfix/workorders/controller/OrdenController.java` |
| OrdenTrabajoServicio | OrdenService | `src/main/java/cl/digitalfix/workorders/service/OrdenService.java` |
| OrdenTrabajoRepositorio | OrdenRepository | `src/main/java/cl/digitalfix/workorders/repository/OrdenRepository.java` |
| CatalogCliente | CatalogoClient | `src/main/java/cl/digitalfix/workorders/client/CatalogoClient.java` |
| ConfiguracionRestClient | RestClientConfig | `src/main/java/cl/digitalfix/workorders/config/client/RestClientConfig.java` |
| ActualizarOrdenSolicitud | ActualizarOrdenRequest | `src/main/java/cl/digitalfix/workorders/dto/request/ActualizarOrdenRequest.java` |
| CambiarEstadoSolicitud | CambioEstadoRequest | `src/main/java/cl/digitalfix/workorders/dto/request/CambioEstadoRequest.java` |
| CrearOrdenSolicitud | NuevaOrdenRequest | `src/main/java/cl/digitalfix/workorders/dto/request/NuevaOrdenRequest.java` |
| RepuestoOrdenSolicitud | RepuestoRequest | `src/main/java/cl/digitalfix/workorders/dto/request/RepuestoRequest.java` |
| DescontarStockCatalogSolicitud | DescontarStockCatalogoRequest | `src/main/java/cl/digitalfix/workorders/dto/request/DescontarStockCatalogoRequest.java` |
| OrdenTrabajoResponse | OrdenResponse | `src/main/java/cl/digitalfix/workorders/dto/response/OrdenResponse.java` |
| OrdenTrabajoMapper | OrdenMapper | `src/main/java/cl/digitalfix/workorders/mapper/OrdenMapper.java` |
| OrdenTrabajoControladorTests | OrdenControllerTests | `src/test/java/cl/digitalfix/workorders/OrdenControllerTests.java` |
| OrdenTrabajoServicioTests | OrdenServiceTests | `src/test/java/cl/digitalfix/workorders/OrdenServiceTests.java` |

Los archivos antiguos se sustituyen por los renombrados; no quedan clases
Java duplicadas ni referencias a los tipos anteriores. Las clases Application
conservan sus nombres para mantener las referencias de arranque de Docker/IDE.
No se crean nuevas capas, clases de negocio ni abstracciones. Se crean tres
informes REFACTOR_LOMBOK.md, uno por servicio, y se actualizan los README.

## Incorporación de Lombok

pom.xml añade org.projectlombok:lombok con scope provided y optional=true,
utilizando la versión 1.18.46 gestionada por Spring Boot 4.1.1. Maven Compiler
configura annotationProcessorPaths explícitamente con ${lombok.version}.
El plugin de Spring Boot excluye Lombok del JAR de ejecución. Los Dockerfile no
necesitan cambios: ya copian pom.xml y src y compilan con JDK 21.

La integración sigue la [documentación oficial de Lombok para Maven](https://projectlombok.org/setup/maven).
No se cambia la versión de Java, Spring Boot ni de dependencias existentes.
No se añade @Data, @Builder, @EqualsAndHashCode ni @ToString.

## Compatibilidad verificada

- Mismos endpoints y métodos HTTP: 9 en BFF, 6 en Workorders y 9 en Catalog.
- Mismos campos y contratos JSON, incluyendo status, oid y repuestos.
- Misma seguridad, Bearer, roles, scopes, claims y autorizaciones.
- Mismas reglas de dominio, validaciones, consultas y transacciones.
- Mismos nombres y anotaciones JPA de tablas, columnas, colecciones y generación de IDs.
- Properties, recursos de prueba, Dockerfile, Compose y .env.example idénticos
  byte por byte al inicio de la tarea (incluidos cambios locales anteriores).
- Entidades y requests mutables conservan las firmas y visibilidad de métodos,
  getters/setters y constructores, comparadas con javap antes/después.
- Comparación de producción sin diferencias fuera de nombres, imports, formato
  y getters/setters/constructores simples reemplazados por Lombok.
- Sin ciclos de dependencias; services sin RestClient/WebClient ni records.
- Ninguna implementación nueva de RabbitMQ, Kafka o Zookeeper. Sus menciones
  documentales en Notify/Audit/Report permanecen fuera de este alcance.

## Validación

Se utilizó Maven Wrapper con Temurin JDK 21.0.12.1 temporal. El wrapper del
checkout no es ejecutable: se invocó con bash, sin modificar su contenido/permisos.
Las dependencias estaban disponibles, por lo que las comprobaciones usaron -o.

```bash
JAVA_HOME=/tmp/digitalfix-jdk21 bash ./mvnw -o clean test
JAVA_HOME=/tmp/digitalfix-jdk21 bash ./mvnw -o -DskipTests package
```

Resultado: **18 tests, 0 fallos, 0 errores, 0 omitidos; package correcto**.

Se verificó procesamiento de anotaciones, inyección de dependencias, contextos
Spring, persistencia con H2, validaciones y serialización/deserialización Jackson.
Los JAR tienen bytecode Java 21 (major 65) y no contienen Lombok en BOOT-INF/lib.
Las pruebas de seguridad del BFF usan JWT firmados y servidores HTTP locales.
Estas comprobaciones no certifican un despliegue remoto en AWS/Entra/Oracle.

## Deuda técnica detectada

| Archivo | Problema | Riesgo | Mejora futura recomendada |
|---|---|---|---|
| `src/main/resources/application.properties` | Datasource Oracle fijo y credencial versionada; no se reproducen valores aquí. | Configuración de despliegue y gestión de credenciales inconsistentes. | Externalizar y revisar rotación en una tarea autorizada; no se modifican properties ahora. |
| `src/main/java/cl/digitalfix/workorders/service/OrdenService.java; client/CatalogoClient.java` | Descuento de stock y actualización de orden abarcan transacciones distintas. | Un fallo después del descuento puede dejar stock consumido sin confirmar la orden. | Definir recuperación/compensación y probar concurrencia en una fase funcional; mantener hoy la idempotencia existente. |
| `src/main/java/cl/digitalfix/workorders/controller/OrdenController.java` | Las lecturas internas confían en el filtrado/autorización del BFF. | Exponer el dominio directamente cambia el límite de confianza. | Revisar autorización sobre recursos en una tarea específica, conservando el acceso interno actual. |
