FROM valkey/valkey:8.1-alpine@sha256:6c54dd3a90f7b0b50ef19a89cb99cbbf7f6e59b9d8909f1bc225b60ca519efc3

COPY --chown=999:1000 infra/budget/valkey/valkey.conf /etc/valkey/valkey.conf

USER 999:1000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=5 \
  CMD valkey-cli --no-auth-warning -a "$VALKEY_ADMIN_PASSWORD" ping | grep -qx PONG

EXPOSE 6379
CMD ["valkey-server", "/etc/valkey/valkey.conf"]
