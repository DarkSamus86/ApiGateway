FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="darksamus"
LABEL description="API Gateway for Library Management System"

RUN apk add --no-cache curl

WORKDIR /app

COPY build/libs/*.jar app.jar

RUN mkdir -p /app/logs

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENV SPRING_PROFILES_ACTIVE=dev
ENV LOG_PATH=/app/logs

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
