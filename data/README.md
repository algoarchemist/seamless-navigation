# data/

Raw and processed datasets (PRD.md Section 24). Not committed in bulk —
see root `.gitignore`.

- `raw/` — IO-VNBD as downloaded, untouched, plus any self-captured
  supplementary recordings (PRD.md Section 24) if IO-VNBD's labels turn
  out to be insufficient for the motion classifier. Nothing placed here
  yet — Phase 4 has not started.
- `processed/` — cleaned/resampled/feature-extracted output of
  `ml/inspect_dataset.py` and `ml/feature_extraction.py`. Derived data
  only; must be reproducible from `raw/` by re-running the pipeline, so
  it is not treated as a source of truth in its own right.
