package com.sih26168.idr.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

private const val MODEL_ASSET_FILENAME = "reacquisition_drift_v1.onnx"

// Verified directly against the exported ONNX file via onnxruntime's
// Python API (session.get_inputs()/get_outputs()) before hardcoding here
// (CLAUDE.md Rule 13 — not guessed), same as VelocityModel.kt. skl2onnx
// uses these same default names regardless of regressor type
// (RandomForestRegressor or, here, LinearRegression).
private const val INPUT_TENSOR_NAME = "input"
private const val OUTPUT_TENSOR_NAME = "variable"

// Must match ml/train_reacquisition_model.py's DRIFT_FEATURE_COLUMNS count/order:
// [outageDurationS, avgPredictedSpeedMps, predictedSpeedStdMps].
private const val FEATURE_COUNT = 3

/**
 * ONNX Runtime Mobile wrapper for the trained reacquisition-drift
 * LinearRegression model — PRD.md Section 17's "AI-based" half of the
 * GNSS+INS Fusion Engine (`ml/train_reacquisition_model.py`/
 * `ml/export_reacquisition_model.py`). Predicts EXPECTED along-track DR
 * position drift (meters) at the moment GNSS reacquires, so
 * `fusion/PositionFusion.kt` can pick an adaptive REACQUISITION blend
 * duration instead of a fixed constant — see that class's
 * `blendDurationForDriftMs` for the mapping and the full "why not a
 * Kalman/EKF filter" reasoning.
 *
 * LinearRegression, not RandomForestRegressor, was the MEASURED choice
 * (CLAUDE.md Rule 3/11) — see `ml/train_reacquisition_model.py`'s own
 * printed comparison: with only 3 features and ~1,200 simulated training
 * samples, the linear model's held-out MAE/RMSE beat both the forest and
 * the best 1-parameter physics-formula baseline.
 *
 * Loads `models/reacquisition_drift_v1.onnx` (bundled Android asset,
 * gitignored-with-an-exception like velocity_v1.onnx — see
 * `models/README.md`) once at construction, then runs single-row
 * inference per REACQUISITION event (NOT per tick — only evaluated the
 * instant REACQUISITION begins, see `fusion/StateEstimator.kt`).
 *
 * Threading: same convention as [VelocityModel] — this class does not
 * manage threading itself; the caller must invoke [predict] off the main
 * thread (CLAUDE.md Android Rule 7). In practice this is called far less
 * often than [VelocityModel.predict] (once per outage, not once per
 * ~10Hz tick), so its main-thread cost (same collecting-coroutine
 * pattern [com.sih26168.idr.fusion.StateEstimator] already uses) is
 * negligible even without a dedicated dispatcher.
 */
class ReacquisitionDriftModel private constructor(
    private val session: OrtSession,
    private val environment: OrtEnvironment,
) {
    companion object {
        fun loadFromAssets(context: Context): ReacquisitionDriftModel {
            val modelBytes = context.assets.open(MODEL_ASSET_FILENAME).use { it.readBytes() }
            val environment = OrtEnvironment.getEnvironment()
            val session = environment.createSession(modelBytes)
            return ReacquisitionDriftModel(session, environment)
        }
    }

    /**
     * @param outageDurationS real elapsed time (seconds) since GNSS was
     *   last trustworthy, up to this reacquisition instant.
     * @param avgPredictedSpeedMps mean DR-estimated speed (m/s) over the
     *   outage — see [com.sih26168.idr.fusion.RunningStats], accumulated
     *   from whichever DR source (ML or physics) was actually active
     *   each tick, matching training's use of the velocity model's own
     *   predictions as the "live on-device signal."
     * @param predictedSpeedStdMps population standard deviation (m/s) of
     *   that same speed stream over the outage (same [RunningStats]
     *   instance, `ddof=0` to match `numpy.std()`'s default used at
     *   training time).
     * @return predicted along-track drift, meters — clamped to >= 0. The
     *   underlying LinearRegression has no non-negativity constraint; a
     *   short/slow simulated outage can produce a small negative raw
     *   prediction from the fitted intercept (confirmed during training —
     *   see that script's own printed sanity range), which is physically
     *   meaningless since drift can't be negative.
     */
    fun predict(outageDurationS: Float, avgPredictedSpeedMps: Float, predictedSpeedStdMps: Float): Float {
        val features = floatArrayOf(outageDurationS, avgPredictedSpeedMps, predictedSpeedStdMps)
        val inputBuffer = FloatBuffer.wrap(features)
        OnnxTensor.createTensor(environment, inputBuffer, longArrayOf(1, FEATURE_COUNT.toLong())).use { inputTensor ->
            session.run(mapOf(INPUT_TENSOR_NAME to inputTensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = result.get(OUTPUT_TENSOR_NAME).get().value as Array<FloatArray>
                return output[0][0].coerceAtLeast(0f)
            }
        }
    }

    fun close() {
        session.close()
    }
}
