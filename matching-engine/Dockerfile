# Build stage: compile and run the tests inside a Maven image
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

# Run stage: only the JRE and the jar, running as a non-root user
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /app/target/matching-engine.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
