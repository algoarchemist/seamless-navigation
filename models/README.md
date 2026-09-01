# models/

Versioned, exported model artifacts (ONNX/LiteRT) — the output of
`ml/export_model.py`/`ml/export_reacquisition_model.py`, consumed by
`android/app/.../VelocityModel.kt`/`ReacquisitionDriftModel.kt` and
(once trained) `MotionClassifierModel.kt`.

Naming convention: `<model>_v<N>.onnx`, e.g. `velocity_v1.onnx`,
`reacquisition_drift_v1.onnx`, `motion_classifier_v1.onnx` — so a demo
regression can be traced back to a specific model version (see
`docs/PROJECT_MAP.md`). Each version bump must be accompanied by the
measured metrics that justified it (PRD.md Section 28) — no
untracked/undocumented model swaps.

`velocity_v1.onnx` (~21MB) and `reacquisition_drift_v1.onnx` (~0.25KB)
ARE committed to this repo, as an explicit exception to the general
"don't commit exported binaries" instinct — see `.gitignore`'s comment on
the same files for the reasoning (short version: a teammate can't
regenerate them from this repo alone anyway, since the IO-VNBD training
data isn't redistributed via git either, so keeping the artifacts that
already exist out of version control only adds friction). Future/larger
model versions should still default to NOT being committed unless the
same reasoning applies.
