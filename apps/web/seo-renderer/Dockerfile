FROM --platform=$BUILDPLATFORM node:22-bookworm-slim@sha256:6c74791e557ce11fc957704f6d4fe134a7bc8d6f5ca4403205b2966bd488f6b3 AS build
WORKDIR /workspace
COPY apps/web/package.json apps/web/package-lock.json ./
RUN --mount=type=cache,id=home-search-seo-npm,target=/root/.npm npm ci
COPY apps/web .
ARG VITE_KAKAO_MAP_APP_KEY
ARG VITE_MARKET_NEWS_ENABLED=false
ENV VITE_KAKAO_MAP_APP_KEY=${VITE_KAKAO_MAP_APP_KEY}
ENV VITE_MARKET_NEWS_ENABLED=${VITE_MARKET_NEWS_ENABLED}
RUN test "${VITE_MARKET_NEWS_ENABLED}" = "true" -o "${VITE_MARKET_NEWS_ENABLED}" = "false" \
    && npx vite build \
    && npm run build:renderer
FROM node:22-bookworm-slim@sha256:6c74791e557ce11fc957704f6d4fe134a7bc8d6f5ca4403205b2966bd488f6b3
ENV NODE_ENV=production PORT=3000
WORKDIR /app
COPY --from=build /workspace/dist/renderer/server.mjs ./server.mjs
COPY --from=build /workspace/dist/index.html ./index.html
USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 CMD node -e "fetch('http://127.0.0.1:3000/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node","server.mjs"]
