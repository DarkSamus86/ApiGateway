FROM gradle:8.7-jdk21 AS build

WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY gradlew ./
RUN chmod +x ./gradlew

RUN ./gradlew dependencies --no-daemon

COPY src ./src

RUN ./gradlew bootJar --no-daemon --stacktrace

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
RUN mkdir -p /app/logs
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENV SPRING_PROFILES_ACTIVE=dev
ENV LOG_PATH=/app/logs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
