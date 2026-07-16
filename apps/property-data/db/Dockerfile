FROM --platform=linux/amd64 redgate/flyway:11.7.2

USER root
RUN mkdir -p /flyway/sql /flyway/conf \
    && chown -R 10001:10001 /flyway/sql /flyway/conf
COPY --chown=10001:10001 apps/property-data/db/migration/api /flyway/sql
COPY --chown=10001:10001 apps/property-data/db/flyway.conf /flyway/conf/flyway.conf

USER 10001:10001
ENTRYPOINT ["/flyway/flyway"]
CMD ["migrate"]
