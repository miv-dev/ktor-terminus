# Stage 1: build fat JAR
FROM gradle:8.14-jdk21 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle gradle
# Cache dependencies first
RUN gradle dependencies -q || true
COPY src src
RUN gradle shadowJar -x test

# Stage 2: runtime
FROM eclipse-temurin:21-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends software-properties-common && \
    add-apt-repository ppa:potassco/stable && \
    apt-get update && \
    apt-get install -y --no-install-recommends clingo && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /app/build/libs/*-all.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
