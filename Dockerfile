# Убираем -alpine, используем стандартный тег (он поддерживает и Apple Silicon, и Intel)
FROM eclipse-temurin:21-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends software-properties-common && \
    add-apt-repository ppa:potassco/stable && \
    apt-get update && \
    apt-get install -y --no-install-recommends clingo && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Копируем JAR (убедитесь, что вы уже запустили ./gradlew shadowJar)
COPY build/libs/*-all.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]