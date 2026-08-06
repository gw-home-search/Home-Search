variable "REGISTRY" {
  default = "local"
}

variable "IMAGE_PREFIX" {
  default = "home-search"
}

variable "GIT_SHA" {
  default = "dev"
}

variable "VERSION" {
  default = "0.0.0-dev"
}

variable "SOURCE_URL" {
  default = "https://github.com/example/home-search"
}

variable "KAKAO_MAP_APP_KEY" {
  default = "build-placeholder"
}

variable "MARKET_NEWS_ENABLED" {
  default = "false"
}

group "default" {
  targets = [
    "property-api",
    "property-batch",
    "property-flyway",
    "admin-api",
    "admin-migration",
    "admin-ops",
    "user-api",
    "user-insight-worker",
    "user-flyway",
    "source-data-migration",
    "public-gateway",
    "admin-gateway",
    "backup",
    "ops-bootstrap",
    "ml",
    "ai",
    "chat-bff",
    "seo-renderer",
    "budget-postgres",
    "budget-valkey",
  ]
}

target "_common" {
  platforms = ["linux/amd64"]
  labels = {
    "org.opencontainers.image.source" = "${SOURCE_URL}"
    "org.opencontainers.image.revision" = "${GIT_SHA}"
    "org.opencontainers.image.version" = "${VERSION}"
  }
}

target "property-api" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/property-data/api/Dockerfile"
  labels = { "org.opencontainers.image.title" = "home-search-property-api" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/property-api:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/property-api:${VERSION}"]
}

target "property-batch" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/property-data/batch/Dockerfile"
  labels = { "org.opencontainers.image.title" = "home-search-property-batch" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/property-batch:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/property-batch:${VERSION}"]
}

target "property-flyway" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/property-data/db/Dockerfile"
  platforms = ["linux/amd64"]
  labels = { "org.opencontainers.image.title" = "home-search-property-flyway" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/property-flyway:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/property-flyway:${VERSION}"]
}

target "admin-api" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/admin/service/Dockerfile"
  target = "api"
  labels = { "org.opencontainers.image.title" = "home-search-admin-api" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/admin-api:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/admin-api:${VERSION}"]
}

target "admin-migration" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/admin/service/Dockerfile"
  target = "migration"
  labels = { "org.opencontainers.image.title" = "home-search-admin-migration" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/admin-migration:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/admin-migration:${VERSION}"]
}

target "admin-ops" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/admin/service/Dockerfile"
  target = "ops"
  labels = { "org.opencontainers.image.title" = "home-search-admin-ops" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/admin-ops:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/admin-ops:${VERSION}"]
}

target "user-api" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/user/service/Dockerfile"
  target = "app"
  labels = { "org.opencontainers.image.title" = "home-search-user-api" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/user-api:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/user-api:${VERSION}"]
}

target "user-insight-worker" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/user/service/Dockerfile"
  target = "worker"
  labels = { "org.opencontainers.image.title" = "home-search-user-insight-worker" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/user-insight-worker:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/user-insight-worker:${VERSION}"]
}

target "user-flyway" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/user/service/Dockerfile"
  target = "flyway"
  platforms = ["linux/amd64"]
  labels = { "org.opencontainers.image.title" = "home-search-user-flyway" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/user-flyway:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/user-flyway:${VERSION}"]
}

target "source-data-migration" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/source-data/Dockerfile"
  labels = { "org.opencontainers.image.title" = "home-search-source-data-migration" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/source-data-migration:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/source-data-migration:${VERSION}"]
}

target "public-gateway" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/web/Dockerfile"
  args = {
    VITE_KAKAO_MAP_APP_KEY = "${KAKAO_MAP_APP_KEY}"
    VITE_MARKET_NEWS_ENABLED = "${MARKET_NEWS_ENABLED}"
  }
  labels = { "org.opencontainers.image.title" = "home-search-public-gateway" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/public-gateway:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/public-gateway:${VERSION}"]
}

target "seo-renderer" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/web/seo-renderer/Dockerfile"
  args = {
    VITE_KAKAO_MAP_APP_KEY = "${KAKAO_MAP_APP_KEY}"
    VITE_MARKET_NEWS_ENABLED = "${MARKET_NEWS_ENABLED}"
  }
  labels = { "org.opencontainers.image.title" = "home-search-seo-renderer" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/seo-renderer:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/seo-renderer:${VERSION}"]
}

target "budget-postgres" {
  inherits = ["_common"]
  context = "."
  dockerfile = "infra/budget/postgres/Dockerfile"
  labels = { "org.opencontainers.image.title" = "home-search-budget-postgres" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/budget-postgres:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/budget-postgres:${VERSION}"]
}

target "budget-valkey" {
  inherits = ["_common"]
  context = "."
  dockerfile = "infra/budget/valkey/Dockerfile"
  labels = { "org.opencontainers.image.title" = "home-search-budget-valkey" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/budget-valkey:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/budget-valkey:${VERSION}"]
}

target "admin-gateway" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/admin/web/Dockerfile"
  labels = { "org.opencontainers.image.title" = "home-search-admin-gateway" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/admin-gateway:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/admin-gateway:${VERSION}"]
}

target "backup" {
  inherits = ["_common"]
  context = "."
  dockerfile = "infra/backup/Dockerfile"
  labels = { "org.opencontainers.image.title" = "home-search-backup" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/backup:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/backup:${VERSION}"]
}

target "ops-bootstrap" {
  inherits = ["_common"]
  context = "."
  dockerfile = "infra/bootstrap/Dockerfile"
  labels = { "org.opencontainers.image.title" = "home-search-ops-bootstrap" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/ops-bootstrap:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/ops-bootstrap:${VERSION}"]
}

target "ml" {
  inherits = ["_common"]
  context = "apps/ml"
  dockerfile = "Dockerfile"
  labels = { "org.opencontainers.image.title" = "home-search-ml" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/ml:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/ml:${VERSION}"]
}

target "ai" {
  inherits = ["_common"]
  context = "apps/ai"
  dockerfile = "Dockerfile"
  labels = { "org.opencontainers.image.title" = "home-search-ai" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/ai:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/ai:${VERSION}"]
}

target "chat-bff" {
  inherits = ["_common"]
  context = "."
  dockerfile = "apps/chat-bff/Dockerfile"
  labels = { "org.opencontainers.image.title" = "home-search-chat-bff" }
  tags = ["${REGISTRY}/${IMAGE_PREFIX}/chat-bff:${GIT_SHA}", "${REGISTRY}/${IMAGE_PREFIX}/chat-bff:${VERSION}"]
}
