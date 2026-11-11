# Imagen multi-stage: compila el frontend, luego el backend (empaquetando el
# build del frontend dentro de los recursos estáticos de Spring Boot) y por
# último arma una imagen de runtime liviana. Permite desplegar la app como
# contenedor y escalar horizontalmente con varias réplicas detrás de un balanceador.

# ---- Etapa 1: build del frontend (Vite) ----
FROM node:25-slim AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---- Etapa 2: build del backend (Maven) ----
FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
# COPY no borra archivos preexistentes: sin este rm, cada build sucesivo de esta
# imagen acumularía los bundles con hash de la build anterior en vez de
# reemplazarlos (ver auditoría F5).
RUN rm -rf src/main/resources/static/assets
COPY --from=frontend-build /app/frontend/dist/ src/main/resources/static/
RUN ./mvnw -B -q clean package -DskipTests

# ---- Etapa 3: runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app
# curl solo para el HEALTHCHECK (se limpia la caché de apt para no engordar la imagen).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# Ejecuta como usuario sin privilegios en vez de root. /app/logs guarda el registro de
# auditoría (logs/audit.log, ver AuditLog.java): montarlo como volumen para conservarlo
# entre despliegues, p. ej. -v pushdb-audit:/app/logs
RUN useradd --system --create-home appuser \
    && mkdir -p /app/logs \
    && chown appuser:appuser /app/logs
VOLUME ["/app/logs"]
COPY --from=backend-build /app/target/*.jar app.jar
USER appuser
EXPOSE 8080
# Docker marca el contenedor como "unhealthy" si la app deja de responder (ISO/IEC 25010
# Fiabilidad). Un orquestador debe usar /actuator/health/liveness y /actuator/health/readiness.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1
# Detrás de un proxy HTTPS, activar el perfil de producción: -e SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "app.jar"]
