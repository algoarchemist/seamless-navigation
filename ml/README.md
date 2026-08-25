# ml/

Offline Python training/eval pipeline (PRD.md Section 23). Nothing in this
directory is implemented yet beyond `requirements.txt` — Phase 4
(dataset inspection + model development) has not started.

Planned files (see `docs/PROJECT_MAP.md` for full detail, status, and
inputs/outputs of each):

- `inspect_dataset.py` — first Phase 4 task, inspect IO-VNBD structure.
- `feature_extraction.py` — Python mirror of the Kotlin `FeatureExtractor`.
- `train_velocity_model.py`, `train_motion_classifier.py` — model training.
- `export_model.py` — ONNX export + output-parity check vs. on-device.

Setup once Phase 4 starts:

```
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```
