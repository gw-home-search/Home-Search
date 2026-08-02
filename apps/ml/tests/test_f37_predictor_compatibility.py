from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

try:
    import tensorflow as tf

    from ml_service.f37_predictor import F37Predictor
except ModuleNotFoundError:
    tf = None
    F37Predictor = None


@unittest.skipIf(tf is None, "ML runtime dependencies are verified in the built image")
class F37PredictorCompatibilityTest(unittest.TestCase):
    def test_legacy_keras_module_failure_uses_archive_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            artifact_dir = Path(temp_dir)
            (artifact_dir / "keras_model.keras").touch()
            predictor = object.__new__(F37Predictor)
            predictor.artifact_dir = artifact_dir
            fallback_model = object()

            with (
                patch.object(
                    tf.keras.models,
                    "load_model",
                    side_effect=TypeError(
                        "Could not deserialize keras.src.engine.functional.Functional"
                    ),
                ),
                patch.object(
                    predictor,
                    "_load_model_from_weights_archive",
                    return_value=fallback_model,
                ) as fallback,
            ):
                self.assertIs(predictor._load_model(), fallback_model)

            fallback.assert_called_once_with(artifact_dir / "keras_model.keras")

    def test_unknown_model_type_error_is_not_hidden(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            artifact_dir = Path(temp_dir)
            (artifact_dir / "keras_model.keras").touch()
            predictor = object.__new__(F37Predictor)
            predictor.artifact_dir = artifact_dir

            with patch.object(
                tf.keras.models,
                "load_model",
                side_effect=TypeError("unrelated model defect"),
            ):
                with self.assertRaisesRegex(TypeError, "unrelated model defect"):
                    predictor._load_model()

    def test_legacy_vocabulary_is_read_from_the_model_archive(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            model_path = Path(temp_dir) / "keras_model.keras"
            with zipfile.ZipFile(model_path, "w") as archive:
                archive.writestr(
                    "assets/_layer_checkpoint_dependencies/string_lookup/vocabulary.txt",
                    "[UNK]\n1111010100\n",
                )

            predictor = object.__new__(F37Predictor)

            self.assertEqual(
                predictor._load_vocabulary(model_path, "legal_dong_code"),
                ["[UNK]", "1111010100"],
            )

    def test_model_inputs_are_all_tensors(self) -> None:
        predictor = object.__new__(F37Predictor)
        predictor.numeric_features = ["area_m2"]
        predictor.embedding_features = ["legal_dong_code"]
        predictor.medians = {"area_m2": 84.5}

        inputs = predictor.make_inputs({}, {"legal_dong_code": "1111010100"})

        self.assertTrue(all(tf.is_tensor(value) for value in inputs.values()))


if __name__ == "__main__":
    unittest.main()
