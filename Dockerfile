# =============================================================
# Smart Attendance • Unified Single-Container Production Image
# Both Frontend (HTML/JS/CSS) & Backend (Spring Boot 3.4 / Java 21)
# =============================================================

# Stage 1: Build Frontend
FROM node:22-alpine AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package.json ./
COPY frontend/scripts ./scripts
COPY frontend/ ./
RUN node scripts/build.mjs

# Stage 2: Build Spring Boot jar with static frontend bundled inside
FROM maven:3.9.9-eclipse-temurin-21 AS backend-builder
WORKDIR /app/backend
COPY backend/pom.xml .
RUN mvn -q dependency:go-offline
COPY backend/src ./src
# Embed built frontend into Spring Boot's classpath static folder
COPY --from=frontend-builder /app/frontend/dist/ ./src/main/resources/static/
RUN mvn -q -B package

# Stage 3: Minimal Secure Production Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 10001 appuser
COPY --from=backend-builder /app/backend/target/attendance-backend-1.0.0.jar app.jar
USER appuser
EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
