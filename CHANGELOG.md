# Changelog

## v0.1.2 — Mino-side Helpers

Script-side support landed: probes can now load four helpers and
write tight, deterministic adversarial scripts on top of them.

* `tests/adv/edge_helpers.clj` -- `now-ms`, `elapsed-ms`,
  `eval-survives`, `ex-shape`, `capture-eval`, `run-quad` (run the
  same source against `--jit=auto/on/off` and `mino-lean`, return
  outputs), `quad-byte-identical?`, `emit-verdict`, `with-seed`.
* `tests/adv/gen.clj` -- seeded RNG + shape generators
  (`gen-int`, `gen-bool`, `gen-char`, `gen-str`, `gen-sym`,
  `gen-kw`, `gen-prim`, `gen-vec`, `gen-map`, `gen-nested`). The
  RNG is a u32 LCG; xorshift64* would be the natural choice but
  mino's BC compiler currently promotes 64-bit bit-shift-left to
  bigint inside compiled fns. Workaround documented in mino's
  `.local/BUGS.md`.
* `tests/adv/invariants.clj` -- invariant predicates shared across
  probes: `closure-i=`, `stm-sum-preserved`, `jit-quad-byte-id=`,
  `reader-idempotent`, `mode-shape-preservation`,
  `help-version-distinct`, `bounded-depth-classified`,
  `buffer-cap-classified`, `diag-isolation`.
* `tests/adv/runner.clj` -- aggregates regression + script probes,
  parses `--seed` / `--mode`, exits non-zero on any probe failure.
  Empty registry produces `{:total 0 :passed 0 :failed 0 ...}`.

End-to-end verification: `mino tests/adv/runner.clj --seed 0 --mode
smoke` boots cleanly and emits the expected zero-probe summary.

## v0.1.1 — Harness Skeleton (C99)

The C-side probe harness lands as a self-contained ANSI C99 library.
Each probe is a function tagged with metadata (name, category,
budget_ms, sanitizer-clean flag) and registered at link time via
`ADV_PROBE_REGISTER`. The driver iterates the registry, runs each
probe, and emits one JSON line per probe plus a final summary.

* `tests/adv/harness.{h,c}` — public API + implementation. Uses
  `clock_gettime(CLOCK_MONOTONIC)` for timing and a seeded xorshift64*
  RNG for deterministic replay. No platform branches beyond pthread
  vs `_WIN32`.
* `tests/adv/driver.c` — `main()` that calls into `adv_driver_main`.
* `tests/adv/embed/adv_smoke.c` — first registered probe; a
  `mino_state_new` + `mino_state_free` round-trip that exists so the
  harness has something to dispatch from day one.
* `lib/mino_tests/tasks/impl.clj` — `build-harness` task helper
  that compiles the harness against the mino submodule's libmino.a,
  with sanitizer variants (asan / ubsan / tsan / cov).
* `MINO_DEV_ROOT` env var lets developers point at a local mino
  working copy that's ahead of the submodule pin.

## v0.1.0 — Repo Init and Submodule Wiring

First tag for the satellite test suite. mino-tests is the home for the
heavy, multi-runtime, concurrency-soak, fuzz, sanitizer-trinity,
coverage, and adversarial probes that don't belong in mino's main
unit-test set.

* Repo created via `gh repo create mino-tests --public`.
* Added mino as a git submodule under `mino/`, pinned at the most
  recent public mino sha.
* Project manifest at `mino.edn` declaring tasks `adv-test`,
  `adv-test-soak`, `adv-test-coverage`, `adv-test-sanitizers`,
  `build-cov`, and `bump-mino`.
* Tasks namespace skeleton at `lib/mino_tests/tasks.clj`. Each task
  body is a stub that prints what it would do; the real bodies land
  in subsequent v0.1.x and v0.2+ releases.
* README documenting the boundary principle, layout, and quickstart.
