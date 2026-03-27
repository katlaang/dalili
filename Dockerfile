# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system spring \
    && useradd --system --gid spring --create-home spring

COPY build/libs/*.jar /app/app.jar

ENV PORT=8181

EXPOSE 8181

USER spring:spring

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -XX:MaxRAMPercentage=75 -jar /app/app.jar"]
