FROM --platform=linux/amd64 redgate/flyway:11.7.2@sha256:b40d022ed6728a400eb46b84e1dd12d7468cdf63a4b83e6094a1768165fc67c6

USER root
RUN mkdir -p /flyway/sql /flyway/conf \
    && chown -R 10001:10001 /flyway/sql /flyway/conf
COPY --chown=10001:10001 apps/property-data/db/migration/api /flyway/sql
COPY --chown=10001:10001 apps/property-data/db/flyway.conf /flyway/conf/flyway.conf

USER 10001:10001
ENTRYPOINT ["/flyway/flyway"]
CMD ["migrate"]
