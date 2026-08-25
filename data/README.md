# data/

Raw and processed datasets (PRD.md Section 24). Not committed in bulk —
see root `.gitignore`.

- `raw/` — IO-VNBD as downloaded, untouched, plus any self-captured
  supplementary recordings (PRD.md Section 24) if IO-VNBD's labels turn
  out to be insufficient for the motion classifier.

  **Re-obtaining IO-VNBD** (gitignored, so a fresh clone starts empty):
  source is https://github.com/onyekpeu/IO-VNBD. The "Synchronised V
  abd S datasets.zip" [sic — typo in the upstream filename] at repo
  root is stored via Git LFS (~194 MB); the GitHub web UI shows it as a
  134-byte pointer file, not the real content. Fetch the actual object
  with `git lfs` or directly via the LFS media endpoint:
  `curl -L -o data/raw/IO-VNBD/Synchronised_V_and_S_datasets.zip
  "https://media.githubusercontent.com/media/onyekpeu/IO-VNBD/master/Synchronised%20V%20abd%20S%20datasets.zip"`
  then verify against the pointer's declared hash
  (`sha256:624003b0bfb3d221114eb262dd02f21f7dba74fb25d8045b4b8ac684956d2855`,
  size 203606286 bytes) before extracting. Also grab
  `README.md`/`README_1.pdf` from the same repo root — `README_1.pdf`
  is the actual data descriptor (column definitions, per-trip scenario
  tags, driver styles) and is the primary source for the Phase 4
  findings below, not something to re-derive by guessing from the CSVs
  alone.
- `processed/` — cleaned/resampled/feature-extracted output of
  `ml/inspect_dataset.py` and `ml/feature_extraction.py`. Derived data
  only; must be reproducible from `raw/` by re-running the pipeline, so
  it is not treated as a source of truth in its own right.
