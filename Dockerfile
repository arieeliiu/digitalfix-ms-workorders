# Compilar el servicio con Java 21
FROM eclipse-temurin:21-jdk AS compilacion
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

COPY src/ src/
RUN ./mvnw -B -DskipTests package

# Ejecutar el servicio sin permisos de administrador
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system digitalfix \
    && useradd --system --gid digitalfix digitalfix

COPY --from=compilacion /app/target/*.jar app.jar

USER digitalfix
ENTRYPOINT ["java", "-jar", "app.jar"]