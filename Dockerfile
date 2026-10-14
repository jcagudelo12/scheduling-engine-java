# Imagen única del motor; SCHED_ROLE decide si la instancia hace ingesta, consultas o ambas.
#   docker build -t sched-engine-java .
# Imagen del simulador de institución (herramienta de desarrollo, ver docs/simulador.md):
#   docker build --target simulator -t sched-simulator-java .
FROM maven:3.9.16-eclipse-temurin-25 AS builder
WORKDIR /app
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B package -DskipTests -pl server -am

FROM maven:3.9.16-eclipse-temurin-25 AS simulator-builder
WORKDIR /app
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B package -DskipTests -pl simulation -am

FROM eclipse-temurin:25-jre AS simulator
RUN useradd --system --no-create-home sched
WORKDIR /app
COPY --from=simulator-builder /app/simulation/target/sched-simulation.jar /app/sched-simulation.jar
# El escenario por defecto se busca en simulation/fixtures/sample.json, relativo a /app.
COPY simulation/fixtures /app/simulation/fixtures
USER sched
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/sched-simulation.jar"]

# La imagen del motor va al final: es la que se construye cuando no se indica --target.
FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --no-create-home sched
COPY --from=builder /app/server/target/sched-server.jar /app/sched-server.jar
USER sched
EXPOSE 8080 50051
HEALTHCHECK --interval=5s --timeout=2s --retries=3 CMD curl -fsS http://localhost:8080/ready || exit 1
# La JVM usa hasta el 75 % de la memoria asignada al contenedor.
# Netty (gRPC) carga librerías nativas; Java 22+ pide declararlo para no advertir.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/sched-server.jar"]
