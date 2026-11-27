# Mock bank - external payout system with runtime fault injection
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 bank
COPY --from=build /src/target/mock-bank-*.jar app.jar
USER bank
ENV PORT=8090 \
    MOCKBANK_FAULT_MODE=ok \
    MOCKBANK_FAULT_ERROR_RATE=1.0 \
    MOCKBANK_FAULT_LATENCY_MS=0 \
    MOCKBANK_FAULT_TIMEOUT_MS=30000
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
