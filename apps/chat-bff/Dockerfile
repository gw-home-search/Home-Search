FROM amazoncorretto:21-alpine@sha256:58c1d555f4ff3be0cfe90d3b4d1762bde080b57afbb71d48657b9d22748cad5b AS build

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

FROM amazoncorretto:21-alpine@sha256:58c1d555f4ff3be0cfe90d3b4d1762bde080b57afbb71d48657b9d22748cad5b

RUN addgroup -S -g 10001 home-search \
    && adduser -S -D -H -u 10001 -G home-search -s /sbin/nologin home-search
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/apps/chat-bff/build/libs/chat-bff.jar /app/application.jar

USER 10001:10001
EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O /dev/null -T 3 http://127.0.0.1:8083/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
