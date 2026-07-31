FROM --platform=$BUILDPLATFORM node:22-bookworm-slim@sha256:6c74791e557ce11fc957704f6d4fe134a7bc8d6f5ca4403205b2966bd488f6b3 AS build

WORKDIR /workspace
COPY apps/web/package.json apps/web/package-lock.json ./
RUN --mount=type=cache,id=home-search-web-npm,target=/root/.npm npm ci
COPY apps/web .

ARG VITE_KAKAO_MAP_APP_KEY
ARG VITE_MARKET_NEWS_ENABLED=false
ENV VITE_KAKAO_MAP_APP_KEY=${VITE_KAKAO_MAP_APP_KEY}
ENV VITE_MARKET_NEWS_ENABLED=${VITE_MARKET_NEWS_ENABLED}
RUN test "${VITE_MARKET_NEWS_ENABLED}" = "true" -o "${VITE_MARKET_NEWS_ENABLED}" = "false" \
    && npm run build

FROM cgr.dev/chainguard/nginx:latest-dev@sha256:22ee56150b99f1d1955637a96f1b0b9a9a6c047bbc48fe5e5b9004155f0e9087

USER 0
RUN apk add --no-cache gettext

ENV PROPERTY_API_HOST=property-api \
    PROPERTY_API_PORT=8080 \
    USER_API_HOST=user-api \
    USER_API_PORT=8082 \
    CHAT_BFF_HOST=chat-bff \
    CHAT_BFF_PORT=8083 \
    NGINX_ENVSUBST_VARIABLES='$PROPERTY_API_HOST $PROPERTY_API_PORT $USER_API_HOST $USER_API_PORT $CHAT_BFF_HOST $CHAT_BFF_PORT'

COPY --from=build /workspace/dist /usr/share/nginx/html
COPY infra/nginx/public-gateway.conf.template /etc/nginx/templates/default.conf.template
COPY infra/nginx/chainguard-nginx.conf /etc/nginx/nginx.conf
COPY --chmod=0555 infra/nginx/chainguard-entrypoint.sh /usr/local/bin/home-search-nginx-entrypoint

USER 65532:65532

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
ENTRYPOINT ["/usr/local/bin/home-search-nginx-entrypoint"]
