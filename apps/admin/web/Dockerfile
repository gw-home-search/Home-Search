FROM node:22-bookworm-slim AS build

WORKDIR /workspace
COPY apps/admin/web/package.json apps/admin/web/package-lock.json ./
RUN --mount=type=cache,id=home-search-admin-web-npm,target=/root/.npm npm ci
COPY apps/admin/web .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine

ENV ADMIN_API_HOST=admin-api \
    ADMIN_API_PORT=8081 \
    NGINX_ENVSUBST_FILTER='^(ADMIN_API_HOST|ADMIN_API_PORT)$'

COPY --from=build /workspace/dist /usr/share/nginx/html
COPY infra/nginx/admin-gateway.conf.template /etc/nginx/templates/default.conf.template

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
