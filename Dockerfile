# Imagen multi-stage: compila el frontend, luego el backend (empaquetando el
# build del frontend dentro de los recursos estáticos de Spring Boot) y por
# último arma una imagen de runtime liviana. Permite desplegar la app como
# contenedor y escalar horizontalmente con varias réplicas detrás de un balanceador.

# ---- Etapa 1: build del frontend (Vite) ----
FROM node:22-slim AS frontend-build
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
COPY --from=frontend-build /app/frontend/dist/ src/main/resources/static/
RUN ./mvnw -B -q clean package -DskipTests

# ---- Etapa 3: runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app
# Ejecuta como usuario sin privilegios en vez de root
RUN useradd --system --create-home appuser
COPY --from=backend-build /app/target/*.jar app.jar
USER appuser
EXPOSE 8080
# El orquestador debe apuntar sus readiness/liveness probes a /actuator/health.
ENTRYPOINT ["java", "-jar", "app.jar"]
