ARG POSTGRES_BASE=postgres:17-alpine@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193

FROM ${POSTGRES_BASE} AS proj-build

ARG PROJ_VERSION=9.8.1
ARG PROJ_SHA256=af5b731c145c1d13c4e3b4eeb7d167e94e845e440f71e3496b4ed8dae0291960
ARG POSTGIS_VERSION=3.5.7
ARG POSTGIS_SHA256=af9ab591854d52a0d1115f90b797ef1cd60d01b85a11ff813073689e332272ff

USER root
RUN apk add --no-cache \
      build-base ca-certificates cmake curl geos-dev json-c-dev libxml2-dev ninja \
      sqlite sqlite-dev
RUN set -eux; \
    curl --fail --location --silent --show-error --retry 3 \
      --output /tmp/proj.tar.gz \
      "https://download.osgeo.org/proj/proj-${PROJ_VERSION}.tar.gz"; \
    echo "${PROJ_SHA256}  /tmp/proj.tar.gz" | sha256sum -c; \
    mkdir /tmp/proj; \
    tar -xzf /tmp/proj.tar.gz --strip-components=1 -C /tmp/proj; \
    cmake -S /tmp/proj -B /tmp/proj/build -G Ninja \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX=/opt/proj \
      -DCMAKE_INSTALL_LIBDIR=lib \
      -DBUILD_PROJSYNC=OFF \
      -DBUILD_TESTING=OFF \
      -DENABLE_CURL=OFF \
      -DENABLE_TIFF=OFF; \
    cmake --build /tmp/proj/build --parallel; \
    cmake --install /tmp/proj/build

FROM proj-build AS postgis-build

RUN apk add --no-cache perl
RUN set -eux; \
    curl --fail --location --silent --show-error --retry 3 \
      --output /tmp/postgis.tar.gz \
      "https://download.osgeo.org/postgis/source/postgis-${POSTGIS_VERSION}.tar.gz"; \
    echo "${POSTGIS_SHA256}  /tmp/postgis.tar.gz" | sha256sum -c; \
    mkdir /tmp/postgis; \
    tar -xzf /tmp/postgis.tar.gz --strip-components=1 -C /tmp/postgis; \
    cd /tmp/postgis; \
    ./configure \
      --disable-nls \
      --with-projdir=/opt/proj \
      --without-address-standardizer \
      --without-protobuf \
      --without-raster \
      --without-sfcgal \
      --without-topology; \
    make with_llvm=no -j1; \
    make with_llvm=no DESTDIR=/opt/postgis install

FROM ${POSTGRES_BASE}

USER root
RUN apk add --no-cache geos json-c libstdc++ libxml2 openssl sqlite-libs \
    && unlink /usr/local/bin/gosu

COPY --from=postgis-build /opt/proj/lib/libproj.so* /usr/local/lib/
COPY --from=postgis-build /opt/proj/share/proj/ /usr/local/share/proj/
COPY --from=postgis-build /opt/postgis/usr/local/lib/postgresql/ /usr/local/lib/postgresql/
COPY --from=postgis-build /opt/postgis/usr/local/share/postgresql/extension/ /usr/local/share/postgresql/extension/

COPY --chown=70:70 --chmod=0555 infra/budget/postgres/budget-postgres-entrypoint.sh /usr/local/bin/budget-postgres-entrypoint
COPY --chown=70:70 --chmod=0555 infra/budget/postgres/init/ /docker-entrypoint-initdb.d/

ENV POSTGRES_INITDB_ARGS="--auth-host=scram-sha-256 --auth-local=trust"

USER 70:70

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD pg_isready -h 127.0.0.1 -p 5432 || exit 1

EXPOSE 5432

ENTRYPOINT ["/usr/local/bin/budget-postgres-entrypoint"]
CMD ["postgres"]
