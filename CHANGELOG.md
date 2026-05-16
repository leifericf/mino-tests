# Changelog

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
