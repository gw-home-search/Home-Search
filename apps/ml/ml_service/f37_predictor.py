from __future__ import annotations

import json
import math
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import h5py
import tensorflow as tf


PROJECT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_ARTIFACT_DIR = PROJECT_DIR / "models" / "best_price_deployment_attempt"
LEGACY_VOCABULARY_ASSETS = {
    "legal_dong_code": "assets/_layer_checkpoint_dependencies/string_lookup/vocabulary.txt",
    "sgg_code": "assets/_layer_checkpoint_dependencies/string_lookup_2/vocabulary.txt",
    "prev_deal_gap_bucket": "assets/_layer_checkpoint_dependencies/string_lookup_4/vocabulary.txt",
}
LEGACY_DESERIALIZATION_ERROR_MARKERS = (
    "keras.src.engine.functional",
    "expected str, bytes or os.PathLike object, not NoneType",
)


@dataclass(frozen=True)
class PredictionResult:
    model_version: str
    predicted_price_per_m2: float
    predicted_deal_amount: float | None
    predicted_price_per_pyeong: float
    raw_residual_log: float
    predicted_log_price_per_m2: float
    interval_low: float | None
    interval_high: float | None
    interval_basis: str | None

    def to_dict(self) -> dict[str, Any]:
        return {
            "modelVersion": self.model_version,
            "predictedPricePerM2": self.predicted_price_per_m2,
            "predictedDealAmount": self.predicted_deal_amount,
            "predictedPricePerPyeong": self.predicted_price_per_pyeong,
            "rawResidualLog": self.raw_residual_log,
            "predictedLogPricePerM2": self.predicted_log_price_per_m2,
            "intervalLow": self.interval_low,
            "intervalHigh": self.interval_high,
            "intervalBasis": self.interval_basis,
        }


class F37Predictor:
    def __init__(self, artifact_dir: str | Path = DEFAULT_ARTIFACT_DIR):
        self.artifact_dir = Path(artifact_dir)
        self.metadata = self._load_json("metadata.json")
        self.schema = self._load_json("feature_schema.json")
        self.medians = {
            key: float(value)
            for key, value in self._load_json("numeric_medians.json").items()
        }
        self.numeric_features = list(self.schema["numeric_features"])
        self.embedding_features = list(self.schema["embedding_features"])
        self.embedding_dims = dict(self.schema["embedding_dims"])
        self.base_log_feature = str(self.schema["base_log_feature"])
        self.model_version = str(self.metadata["model_version"])
        self.model = self._load_model()

    def _load_json(self, name: str) -> dict[str, Any]:
        path = self.artifact_dir / name
        if not path.exists():
            raise FileNotFoundError(path)
        return json.loads(path.read_text(encoding="utf-8"))

    def _load_model(self) -> tf.keras.Model:
        model_path = self.artifact_dir / "keras_model.keras"
        try:
            return tf.keras.models.load_model(model_path, compile=False)
        except TypeError as exc:
            if not any(
                marker in str(exc)
                for marker in LEGACY_DESERIALIZATION_ERROR_MARKERS
            ):
                raise
            return self._load_model_from_weights_archive(model_path)

    def _load_model_from_weights_archive(self, model_path: Path) -> tf.keras.Model:
        inputs: dict[str, tf.keras.KerasTensor] = {}
        embedding_outputs = []
        embedding_layers = []

        for feature in self.embedding_features:
            vocabulary = self._load_vocabulary(model_path, feature)
            if not vocabulary or vocabulary[0] != "[UNK]":
                raise ValueError(f"legacy vocabulary must start with [UNK]: {feature}")
            feature_input = tf.keras.Input(shape=(1,), dtype=tf.string, name=f"{feature}_input")
            inputs[f"{feature}_input"] = feature_input
            lookup = tf.keras.layers.StringLookup(
                vocabulary=vocabulary[1:],
                num_oov_indices=1,
                mask_token=None,
                output_mode="int",
                name=f"{feature}_lookup",
            )(feature_input)
            embedding_layer = tf.keras.layers.Embedding(
                input_dim=len(vocabulary),
                output_dim=int(self.embedding_dims[feature]),
                name=f"{feature}_embedding",
            )
            embedding_layers.append(embedding_layer)
            embedding_outputs.append(
                tf.keras.layers.Flatten(name=f"{feature}_flatten")(embedding_layer(lookup))
            )

        numeric_input = tf.keras.Input(
            shape=(len(self.numeric_features),),
            dtype=tf.float32,
            name="numeric_input",
        )
        inputs["numeric_input"] = numeric_input
        normalization = tf.keras.layers.Normalization(axis=-1, name="numeric_normalization")
        normalized = normalization(numeric_input)
        x = tf.keras.layers.Concatenate(axis=-1, name="feature_concat")([
            normalized,
            *embedding_outputs,
        ])
        dense = tf.keras.layers.Dense(
            128,
            activation="relu",
            kernel_regularizer=tf.keras.regularizers.L2(0.000009999999747378752),
            name="dense",
        )
        dense_1 = tf.keras.layers.Dense(
            64,
            activation="relu",
            kernel_regularizer=tf.keras.regularizers.L2(0.000009999999747378752),
            name="dense_1",
        )
        dense_2 = tf.keras.layers.Dense(1, activation="linear", name="dense_2")
        x = dense(x)
        x = tf.keras.layers.Dropout(0.1, name="dropout")(x)
        x = dense_1(x)
        x = tf.keras.layers.Dropout(0.05, name="dropout_1")(x)
        output = dense_2(x)
        model = tf.keras.Model(inputs=inputs, outputs=output)

        sample_inputs = {
            "numeric_input": tf.zeros((1, len(self.numeric_features)), dtype=tf.float32),
            **{
                f"{feature}_input": tf.constant([["missing"]])
                for feature in self.embedding_features
            },
        }
        model(sample_inputs, training=False)
        self._assign_archive_weights(
            model_path,
            embedding_layers=embedding_layers,
            normalization=normalization,
            dense_layers=[dense, dense_1, dense_2],
        )
        return model

    def _load_vocabulary(self, model_path: Path, feature: str) -> list[str]:
        asset_path = LEGACY_VOCABULARY_ASSETS.get(feature)
        if asset_path is None:
            raise ValueError(f"unsupported legacy embedding feature: {feature}")
        with zipfile.ZipFile(model_path) as archive:
            try:
                info = archive.getinfo(asset_path)
            except KeyError as exc:
                raise ValueError(f"missing legacy vocabulary asset: {feature}") from exc
            if info.is_dir() or info.file_size > 1024 * 1024:
                raise ValueError(f"invalid legacy vocabulary asset: {feature}")
            try:
                vocabulary = archive.read(info).decode("utf-8").splitlines()
            except UnicodeDecodeError as exc:
                raise ValueError(f"invalid legacy vocabulary encoding: {feature}") from exc
        if not vocabulary or vocabulary[0] != "[UNK]":
            raise ValueError(f"legacy vocabulary must start with [UNK]: {feature}")
        return vocabulary

    def _assign_archive_weights(
        self,
        model_path: Path,
        *,
        embedding_layers: list[tf.keras.layers.Layer],
        normalization: tf.keras.layers.Normalization,
        dense_layers: list[tf.keras.layers.Layer],
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with zipfile.ZipFile(model_path) as archive:
                weights_path = Path(archive.extract("model.weights.h5", temp_dir))
            with h5py.File(weights_path, "r") as weights:
                dependencies = weights["_layer_checkpoint_dependencies"]
                for layer, group_name in zip(
                    embedding_layers,
                    ["embedding", "embedding_2", "embedding_4"],
                    strict=True,
                ):
                    layer.set_weights([dependencies[group_name]["vars"]["0"][()]])
                normalization.set_weights([
                    dependencies["normalization"]["vars"]["0"][()],
                    dependencies["normalization"]["vars"]["1"][()],
                    dependencies["normalization"]["vars"]["2"][()],
                ])
                for layer, group_name in zip(
                    dense_layers,
                    ["dense", "dense_2", "dense_4"],
                    strict=True,
                ):
                    layer.set_weights([
                        dependencies[group_name]["vars"]["0"][()],
                        dependencies[group_name]["vars"]["1"][()],
                    ])

    def make_inputs(
        self,
        numeric_features: dict[str, Any],
        embedding_features: dict[str, Any],
    ) -> dict[str, Any]:
        numeric_values = []
        for feature in self.numeric_features:
            value = numeric_features.get(feature, self.medians.get(feature))
            if value is None or (isinstance(value, float) and math.isnan(value)):
                value = self.medians.get(feature, 0.0)
            numeric_values.append(float(value))

        inputs: dict[str, Any] = {
            "numeric_input": tf.convert_to_tensor([numeric_values], dtype=tf.float32),
        }
        for feature in self.embedding_features:
            value = embedding_features.get(feature, "missing")
            if value is None:
                value = "missing"
            inputs[f"{feature}_input"] = tf.convert_to_tensor([[str(value)]], dtype=tf.string)
        return inputs

    def predict(
        self,
        numeric_features: dict[str, Any],
        embedding_features: dict[str, Any],
        *,
        base_log_value: float | None = None,
        area_m2: float | None = None,
        interval_pct: float | None = None,
        interval_basis: str | None = None,
    ) -> PredictionResult:
        if base_log_value is None:
            base_value = numeric_features.get(
                self.base_log_feature,
                self.medians.get(self.base_log_feature),
            )
            if base_value is None:
                raise ValueError(f"base_log_value or {self.base_log_feature} is required")
            base_log_value = float(base_value)

        raw_residual = float(
            self.model.predict(
                self.make_inputs(numeric_features, embedding_features),
                verbose=0,
            ).reshape(-1)[0]
        )
        predicted_log = float(base_log_value + raw_residual)
        predicted_price_per_m2 = float(math.exp(predicted_log))
        predicted_deal_amount = (
            float(predicted_price_per_m2 * area_m2)
            if area_m2 is not None
            else None
        )
        interval_low = interval_high = None
        if predicted_deal_amount is not None and interval_pct is not None:
            interval = max(0.0, float(interval_pct))
            interval_low = float(predicted_deal_amount * max(0.0, 1.0 - interval))
            interval_high = float(predicted_deal_amount * (1.0 + interval))

        return PredictionResult(
            model_version=self.model_version,
            predicted_price_per_m2=predicted_price_per_m2,
            predicted_deal_amount=predicted_deal_amount,
            predicted_price_per_pyeong=predicted_price_per_m2 * 3.305785,
            raw_residual_log=raw_residual,
            predicted_log_price_per_m2=predicted_log,
            interval_low=interval_low,
            interval_high=interval_high,
            interval_basis=interval_basis,
        )

    def predict_payload(self, payload: dict[str, Any]) -> dict[str, Any]:
        result = self.predict(
            payload.get("numeric_features", {}),
            payload.get("embedding_features", {}),
            base_log_value=payload.get("base_log_value"),
            area_m2=payload.get("area_m2"),
            interval_pct=payload.get("interval_pct"),
            interval_basis=payload.get("interval_basis"),
        )
        out = result.to_dict()
        if "transaction_id" in payload:
            out["transactionId"] = payload["transaction_id"]
        return out
