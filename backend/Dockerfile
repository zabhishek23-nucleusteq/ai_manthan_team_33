# Build stage: Maven + JDK 17, dependencies cached in their own layer
FROM maven:3-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Run stage: JRE only, non-root user
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /src/target/*.jar app.jar
USER app
EXPOSE 8080
# Health check lives in docker-compose.yml (L6): /actuator/health/readiness
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
