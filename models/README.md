# models/

Versioned, exported model artifacts only (ONNX/LiteRT) — the output of
`ml/export_model.py`, consumed by `android/app/.../VelocityModel.kt` and
`MotionClassifierModel.kt`. Nothing exported yet (Phase 4/5 not started).

Naming convention: `<model>_v<N>.onnx`, e.g. `velocity_v1.onnx`,
`motion_classifier_v1.onnx` — so a demo regression can be traced back to
a specific model version (see `docs/PROJECT_MAP.md`). Each version bump
must be accompanied by the measured metrics that justified it
(PRD.md Section 28) — no untracked/undocumented model swaps.
