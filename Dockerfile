# Build stage
FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -ntp clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# curl is used by the Compose readiness check.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/*.jar /app/app.jar

RUN mkdir -p /app/data/logs/archive && chown -R 10001:10001 /app/data

USER 10001:10001

ENV SERVER_ADDRESS=0.0.0.0
ENV SERVER_PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
