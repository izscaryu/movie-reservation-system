# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — build the executable jar with the project's own Maven wrapper.
# Full JDK here (we compile); the runtime stage drops to a slim JRE.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Copy only the wrapper + pom first and warm the dependency cache. Because
# this layer depends solely on pom.xml, editing src/ later reuses it instead
# of re-downloading every dependency on each build (Docker layer caching).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

# Now copy the sources and build. Tests need a real MySQL (Testcontainers) and
# a Docker daemon, which aren't available in the build sandbox, so skip them
# here — the suite runs via `./mvnw test` on a dev machine, not at image build.
COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

# ---------------------------------------------------------------------------
# Stage 2 — minimal runtime: just a JRE + the jar, run as a non-root user.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as an unprivileged user, never root.
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
USER app

# Copy just the fat jar from the build stage (the *.jar glob skips the
# spring-boot-maven-plugin's *.jar.original).
COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
