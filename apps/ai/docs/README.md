# AI reference contract docs

`asciidoc/`은 tracked source contract 설명이고 `examples/`는 secret과 개인정보가
없는 sanitized fixture다. 생성물은 `apps/ai/build/reference-docs/` 아래에만 둔다.

```bash
ops/build-reference-docs.sh --check
ops/serve-reference-docs.sh
```

generator는 pytest collection을 사용하지 않고 `config/reference_sources.toml`과
tracked example만 읽는다. provider network, DB, MinIO, credential은 필요하지 않다.
