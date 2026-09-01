# ml/

Offline Python training/eval pipeline (PRD.md Section 23). See
`docs/PROJECT_MAP.md` for the full detail, status, and inputs/outputs of
each file — this is just the map of what's here.

Implemented and real (Phase 4 done for the velocity model):

- `inspect_dataset.py` — inspects IO-VNBD structure.
- `feature_extraction.py` — Python mirror of the Kotlin `FeatureExtractor`.
- `train_velocity_model.py` — trains the velocity model against IO-VNBD;
  the real, measured result (4.2x MAE improvement over the physics
  baseline) is documented in `docs/PROJECT_MAP.md`.
- `export_model.py` — ONNX export + output-parity check vs. on-device
  inference. Its output, `models/velocity_v1.onnx`, is committed to this
  repo (see `models/README.md` for why) — you do not need to re-run this
  pipeline just to build and run the Android app.
- `train_reacquisition_model.py` / `export_reacquisition_model.py` — PRD
  Section 17's "AI-based" GNSS+INS fusion half: a small LinearRegression
  predicting expected along-track DR drift (meters) at GNSS
  reacquisition, from outage duration + the velocity model's own
  predicted-speed statistics. Simulates outages on IO-VNBD (no real
  outages exist in this dataset) using the already-trained velocity
  model's predictions. The real, measured result (LinearRegression beats
  both RandomForestRegressor and the best 1-parameter physics-formula
  baseline on held-out trips) is documented in `docs/PROJECT_MAP.md`. Its
  output, `models/reacquisition_drift_v1.onnx` (~0.25KB), is committed
  for the same reason `velocity_v1.onnx` is.

Still planned, blocked on more self-captured labeled data:

- `train_motion_classifier.py` — the real trained Pothole/Turning/
  Cruising classifier. Deterministic stand-ins are wired into the app
  meanwhile (`android/.../motion/`), not this trained model.

Setup (only needed if you're touching the training pipeline itself, not
just building/running the Android app):

```
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

The raw IO-VNBD dataset (`data/raw/IO-VNBD/`) is gitignored and not
redistributed via this repo — see `data/README.md` for the re-download
path if you need to retrain from scratch.
