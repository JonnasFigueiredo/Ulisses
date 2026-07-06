# Build multi-stage: compila com Maven, roda com JRE enxuto.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Os modelos ONNX sao baixados no primeiro start para /app/models (monte um volume para cache).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
