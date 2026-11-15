# Build stage (maven:3.9-eclipse-temurin-25, digest read 2026-11-15)
FROM maven:3.9-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress package -DskipTests

# Runtime stage (eclipse-temurin:25-jre, digest read 2026-11-15)
FROM eclipse-temurin:25-jre@sha256:a640968ae55027b322cedd5f8228b01a84baff0b9e9360e47601c6a111ba1699
WORKDIR /app
RUN useradd --system --no-create-home --uid 10001 app
COPY --from=build /app/target/calendar-sim.jar ./calendar-sim.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/calendar-sim.jar"]
