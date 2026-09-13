# --- Build stage ---
# Tests are skipped here on purpose: CI (.github/workflows/ci.yml) runs
# `mvn clean verify` against the same revision before this image is ever
# built, so this Dockerfile isn't the only place tests run - it shouldn't
# be treated as full validation on its own though (FirstFood_V2_Phase1_
# Review.md #12).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

COPY pom.xml ./
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S firstfood && adduser -S firstfood -G firstfood
COPY --from=build /app/target/*.jar app.jar
RUN chown firstfood:firstfood app.jar
USER firstfood

EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=production

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
