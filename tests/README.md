# tests/

Mirrors CLAUDE.md's Testing Rules (18-20) and PRD.md Section 27.

- `unit/` — deterministic math: coordinate transforms, heading/position
  integration, filters, GNSS-outage state-machine transitions. Required
  before any downstream module relies on that math (CLAUDE.md Rule 19).
- `ml/` — dataset split correctness, training reproducibility, Python-vs
  on-device inference output-parity, latency, model size.
- `integration/` — sensors -> preprocessing -> ML -> state estimator ->
  navigation, run against a recorded sensor log rather than live sensors.

Empty until the corresponding implementation exists — a test with
nothing to test would just be scaffolding noise.
