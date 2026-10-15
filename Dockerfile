ARG GRADLE_VERSION=9.8.0
FROM gradle:${GRADLE_VERSION}-jdk21 AS build
WORKDIR /workspace
COPY backend/gradle backend/gradle
COPY backend/build.gradle backend/settings.gradle ./
COPY backend/src ./src
RUN gradle --no-daemon :bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home pesaguard
COPY --from=build /workspace/build/libs/pesaguard-developer-platform.jar /app/app.jar
COPY --chown=10001:10001 backend/render-entrypoint.sh /app/entrypoint.sh
USER 10001
EXPOSE 8080
ENTRYPOINT ["sh", "/app/entrypoint.sh"]
