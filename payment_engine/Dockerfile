# ═══════════════════════════════════════════════════════════════════
# Multi-Stage Dockerfile — Distributed Payment Processing Engine
# ═══════════════════════════════════════════════════════════════════
#
# Stage 1 (build): Uses Maven to compile + package the JAR
# Stage 2 (run):   Uses a slim JRE-only image to run the JAR
#
# WHY MULTI-STAGE?
# - Build image has Maven + JDK (~500MB) — we don't ship that
# - Runtime image has only JRE (~200MB) — smaller, faster, safer
# - No build tools in production = smaller attack surface
#
# BUILD:  docker build -t payment-engine .
# RUN:    docker compose up (see docker-compose.yml)
# ═══════════════════════════════════════════════════════════════════

# ─── Stage 1: Build ───
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Copy Maven wrapper and POM first (Docker layer caching)
# If only source code changes, Maven dependencies are cached
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code and build
COPY src/ src/
RUN ./mvnw package -DskipTests -B

# ─── Stage 2: Runtime ───
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Create a non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy only the built JAR from stage 1
COPY --from=build /app/target/*.jar app.jar

# Switch to non-root user
USER appuser

# Expose the application port
EXPOSE 8080

# Health check — Spring Boot actuator or our custom endpoint
HEALTHCHECK --interval=30s --timeout=10s --retries=3 --start-period=40s \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/v1/health || exit 1

# JVM tuning for containers:
# - MaxRAMPercentage: Use 75% of container memory limit
# - UseContainerSupport: Detect container memory limits (default since JDK 10)
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
