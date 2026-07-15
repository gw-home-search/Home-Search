#!/bin/sh
set -eu

model_path="${F37_ARTIFACT_DIR:-/model}/keras_model.keras"
if [ ! -r "$model_path" ]; then
    echo "상태: Fail - F37_ARTIFACT_DIR의 keras_model.keras를 UID 10001이 읽을 수 있어야 합니다." >&2
    exit 1
fi

exec "$@"
