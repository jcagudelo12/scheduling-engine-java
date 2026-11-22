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
COPY --from=builder /app/server/target/sched-server-exec.jar /app/sched-server.jar
USER sched
# 8080: HTTP de los estudiantes; 50051: gRPC de ingesta; 8081: administración y métricas
# (/actuator/health y /actuator/prometheus, sin token): no debe publicarse fuera de la red interna.
EXPOSE 8080 8081 50051
HEALTHCHECK --interval=5s --timeout=2s --retries=3 CMD curl -fsS http://localhost:8080/ready || exit 1
# La JVM usa hasta el 75 % de la memoria asignada al contenedor.
# Recolector Serial, explícito: una réplica de ~2 núcleos y heap pequeño pierde con G1 (hilos concurrentes, barreras y
# recolecciones mixtas): -12 % de CPU por solicitud y p99 en estado estable de ~6 ms contra ~18 ms a 4.000/s
# (bench/results/profile-server.md, "Por qué se desborda la latencia con token"). Con menos de 2 núcleos o menos de
# 1.792 MB la JVM ya lo elegiría sola; así no depende de los límites del contenedor. Un recolector distinto (-XX:+UseG1GC...) en
# JAVA_TOOL_OPTIONS choca con este y la JVM no arranca: para cambiarlo, redefina el ENTRYPOINT.
# Netty (gRPC) carga librerías nativas; Java 22+ pide declararlo para no advertir.
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=75", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/sched-server.jar"]
