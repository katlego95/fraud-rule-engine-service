# Exact tags rather than :latest. Digest pinning is the production hardening step and is noted
# in the README; exact tags keep the assessment build reproducible without a private mirror.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Resolve dependencies as their own layer so source changes do not re-download the world.
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

FROM eclipse-temurin:25-jre AS runtime

RUN useradd --system --create-home --uid 10001 fraud
USER fraud
WORKDIR /app

COPY --from=build --chown=fraud:fraud /build/target/fraud-rule-engine-*.jar /app/application.jar

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health/readiness || exit 1"]

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
