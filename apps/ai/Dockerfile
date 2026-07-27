FROM ghcr.io/astral-sh/uv:0.9.28 AS uv

FROM python:3.14-slim

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

RUN groupadd --system home-ai \
    && useradd --system --gid home-ai --home-dir /app --shell /usr/sbin/nologin home-ai

COPY --from=uv /uv /uvx /usr/local/bin/

WORKDIR /app
COPY --chown=home-ai:home-ai pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY --chown=home-ai:home-ai ai_service ./ai_service
COPY --chown=home-ai:home-ai config ./config
RUN uv sync --frozen --no-dev

USER home-ai
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)" || exit 1

CMD ["uvicorn", "ai_service.main:app", "--host", "0.0.0.0", "--port", "8000"]
