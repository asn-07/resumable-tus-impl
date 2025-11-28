# ==========================================================
# Stage 1: Build TUS Upload Service using prebuilt common jar
# ==========================================================
FROM maven:3.9.5-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Define Maven local repo path
ENV MAVEN_OPTS="-Dmaven.repo.local=/app/.m2/repository"

# Copy the main project
COPY tus-resumable-upload-service ./tus-resumable-upload-service

# Copy prebuilt common-service jar into local maven repo manually
RUN mkdir -p /app/.m2/repository/com/tus/upload/tus-common-service/0.0.1-SNAPSHOT/
COPY tus-common-service/target/tus-common-service-0.0.1-SNAPSHOT.jar \
     /app/.m2/repository/com/tus/upload/tus-common-service/0.0.1-SNAPSHOT/tus-common-service-0.0.1-SNAPSHOT.jar

# Build the TUS upload service project using the prebuilt jar
RUN mvn -f tus-resumable-upload-service/pom.xml clean package -DskipTests -Dmaven.repo.local=/app/.m2/repository


# ==========================================================
# Stage 2: Runtime Image
# ==========================================================
FROM eclipse-temurin:21-jre-alpine

# Install curl for health check
RUN apk add --no-cache curl

# Create non-root user and uploads folder
RUN addgroup --system spring && adduser --system spring --ingroup spring && \
    mkdir -p /usr/src/app/upload/tus && \
    chown -R spring:spring /usr/src/app/upload/tus && \
    chmod -R 770 /usr/src/app/upload/tus

USER spring:spring
WORKDIR /app

# Copy final JAR
COPY --from=builder /app/tus-resumable-upload-service/target/*.jar app.jar

# Expose service port
EXPOSE 8080

# Declare persistent volume for shared uploads
RUN mkdir -p /usr/src/app/upload/tus
VOLUME /usr/src/app/upload/tus

# Healthcheck for Spring Boot
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "-Djava.security.egd=file:/dev/./urandom", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "app.jar", "--spring.profiles.active=docker-deployment"]
#ENTRYPOINT ["java", "-jar", "-Djava.security.egd=file:/dev/./urandom", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "app.jar", "--spring.profiles.active=docker-local"]
