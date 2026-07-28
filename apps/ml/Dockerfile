FROM ghcr.io/astral-sh/uv:0.9.28@sha256:59240a65d6b57e6c507429b45f01b8f2c7c0bbeee0fb697c41a39c6a8e3a4cfb AS uv

FROM cgr.dev/chainguard/wolfi-base:latest@sha256:003627df3c1e1bba0c4116afcddb314aca9594ee2328c7e876a8081a6c988b2e

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_CACHE_DIR=/app/.cache/uv \
    F37_ARTIFACT_DIR=/model

WORKDIR /app

USER 0
RUN apk add --no-cache ca-certificates libstdc++ python-3.13 \
    && addgroup -S -g 10001 ml \
    && adduser -S -D -H -u 10001 -G ml -s /sbin/nologin ml \
    && mkdir -p /app/.cache/uv \
    && chown -R 10001:10001 /app
COPY --from=uv /uv /uvx /usr/local/bin/

USER 10001:10001
COPY --chown=10001:10001 requirements.lock ./requirements.lock
RUN uv venv --python /usr/bin/python3.13 /app/.venv \
    && uv pip install --python /app/.venv/bin/python --requirement requirements.lock \
    && uv pip check --python /app/.venv/bin/python \
    && find /app/.cache/uv -depth -delete

COPY --chown=10001:10001 ml_service ./ml_service
COPY --chmod=0555 docker-entrypoint.sh /usr/local/bin/ml-entrypoint

EXPOSE 8001

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8001/health', timeout=3)" || exit 1

ENTRYPOINT ["/usr/local/bin/ml-entrypoint"]
CMD ["uvicorn", "ml_service.main:app", "--host", "0.0.0.0", "--port", "8001"]
