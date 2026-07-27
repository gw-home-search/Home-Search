FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
COPY apps/chat-bff apps/chat-bff
COPY apps/property-data/gradlew apps/property-data/gradlew
COPY apps/property-data/gradle apps/property-data/gradle
COPY libs/security-jwt-core libs/security-jwt-core
COPY libs/user-auth-contract libs/user-auth-contract
RUN --mount=type=cache,id=home-search-gradle,target=/root/.gradle \
    cd apps/chat-bff \
    && chmod +x gradlew \
    && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && apt-get clean \
    && groupadd --gid 10001 home-search \
    && useradd --uid 10001 --gid 10001 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin home-search
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/apps/chat-bff/build/libs/chat-bff.jar /app/application.jar

USER 10001:10001
EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 3 http://127.0.0.1:8083/actuator/health/readiness >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
