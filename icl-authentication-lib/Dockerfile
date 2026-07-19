# Multi-stage build: compile the Ktor fat jar, then run it on a slim JRE.
FROM gradle:8.9-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :server:buildFatJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/server/build/libs/*-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
