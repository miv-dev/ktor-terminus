# Убираем -alpine, используем стандартный тег (он поддерживает и Apple Silicon, и Intel)
FROM eclipse-temurin:21-jre

WORKDIR /app

# Копируем JAR (убедитесь, что вы уже запустили ./gradlew shadowJar)
COPY build/libs/*-all.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]