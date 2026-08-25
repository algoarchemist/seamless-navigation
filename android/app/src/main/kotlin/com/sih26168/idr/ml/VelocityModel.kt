package com.sih26168.idr.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

private const val MODEL_ASSET_FILENAME = "velocity_v1.onnx"

// Verified directly against the exported ONNX file via onnxruntime's
// Python API (`session.get_inputs()/get_outputs()`) before hardcoding
// here (CLAUDE.md Rule 13 — not guessed). "variable" is skl2onnx's
// default output name for a scikit-learn regressor, not a made-up name.
private const val INPUT_TENSOR_NAME = "input"
private const val OUTPUT_TENSOR_NAME = "variable"

// Must match FeatureExtractor.kt's output length / ml/train_velocity_model.py's FEATURE_COLUMNS count.
private const val FEATURE_COUNT = 13

/**
 * ONNX Runtime Mobile wrapper for the trained velocity regression model
 * (PRD.md Section 13/26). Loads `models/velocity_v1.onnx` (bundled as
 * an Android asset — gitignored like the rest of the model artifacts,
 * see docs/PROJECT_MAP.md for the regenerate-and-copy step) once at
 * construction, then runs single-row inference per tick.
 *
 * Threading: this class does NOT manage threading itself — CLAUDE.md
 * Android Rule 7 requires the caller to invoke [predict] off the main
 * thread (the still-PLANNED ml/MlVelocityRepository.kt wiring is
 * responsible for that, matching SensorRepository's existing
 * background-HandlerThread pattern).
 */
class VelocityModel private constructor(
    private val session: OrtSession,
    private val environment: OrtEnvironment,
) {
    companion object {
        fun loadFromAssets(context: Context): VelocityModel {
            val modelBytes = context.assets.open(MODEL_ASSET_FILENAME).use { it.readBytes() }
            val environment = OrtEnvironment.getEnvironment()
            val session = environment.createSession(modelBytes)
            return VelocityModel(session, environment)
        }
    }

    /**
     * @param features a [FEATURE_COUNT]-element vector in
     *   FeatureExtractor.kt's documented order (==
     *   ml/train_velocity_model.py's FEATURE_COLUMNS order — this
     *   order is load-bearing, see that class's doc).
     * @return predicted forward velocity, m/s.
     */
    fun predict(features: FloatArray): Float {
        require(features.size == FEATURE_COUNT) {
            "expected $FEATURE_COUNT features, got ${features.size}"
        }
        val inputBuffer = FloatBuffer.wrap(features)
        OnnxTensor.createTensor(environment, inputBuffer, longArrayOf(1, FEATURE_COUNT.toLong())).use { inputTensor ->
            session.run(mapOf(INPUT_TENSOR_NAME to inputTensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = result.get(OUTPUT_TENSOR_NAME).get().value as Array<FloatArray>
                return output[0][0]
            }
        }
    }

    fun close() {
        session.close()
    }
}
