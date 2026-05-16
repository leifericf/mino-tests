# Changelog

## v0.2.0 — Migration In: Concurrency-Heavy + Async Soak

The first batch from mino v0.253.0 lands here. 313 tests / 538
assertions move from mino's tests/ to `tests/migrated/`, pairing
with the unit-test split codified in mino's CHANGELOG entry for
v0.253.0.

Files imported:

  - `migrated/stm_concurrent_test.clj` — multi-thread STM
    contention; commit invariants under load.
  - `migrated/host_threads_test.clj` — host-thread budget,
    lifecycle, pool registration.
  - `migrated/agent_test.clj` (511 lines) — agent fan-out, send
    vs send-off, watch dispatch.
  - `migrated/regex_reentrant_test.clj` — multi-thread regex
    compile + match.
  - `migrated/async_alts_test.clj` (alts!! / alts! / priority)
  - `migrated/async_api_test.clj` (basic API surface)
  - `migrated/async_blocking_test.clj` (<!! / >!! semantics)
  - `migrated/async_buffer_test.clj` (buffer policies + behaviour)
  - `migrated/async_combinators_test.clj` (mix, merge, pipe,
    onto-chan, etc.)
  - `migrated/async_conformance_test.clj` (1056 lines, full
    cross-port Clojure parity)
  - `migrated/async_go_test.clj` (go-block stress)
  - `migrated/async_mult_pub_test.clj` (mult / tap / pub / sub)
  - `migrated/async_timer_test.clj` (timer scheduling)

* `tests/run_migrated.clj` — aggregator. Loads suite-mode, requires
  every migrated file, runs the registry once. Same shape as mino's
  `tests/run.clj`.

Verified end-to-end: `mino tests/run_migrated.clj` reports
`313 tests, 538 assertions: 538 passed, 0 failed, 0 errors`.

## v0.1.3 — Cross-state Topology Library

Cross-runtime test topologies land as C99 source. Ring / star / mesh
of N states with per-node mutexes; messages travel via `mino_clone`
into the destination's inbox so its GC roots stay clean.

* `tests/adv/topology.{h,c}` -- `adv_topo_build`, `adv_topo_destroy`,
  `adv_topo_post`, `adv_topo_step`, `adv_topo_processed`. Each node
  owns a `mino_state_t`, a pthread mutex, and an inbox of cloned
  values from peers. Successor wiring is data-driven via the
  `next[]` table so the step rule is uniform across shapes.
* `tests/adv/embed/adv_topo_ring_smoke.c` -- 3-state ring smoke
  probe. Posts an int at node 0, steps until quiescent, asserts
  every node processed at least one message. Sanitizer-clean.
* `lib/mino_tests/tasks/impl.clj` -- `build-harness` task helper.
  Cycle Z.3 lands the source + the build command; the actual link
  step waits on a libmino.a shim that lands in Cycle B v0.3.0
  (mino doesn't currently produce a static lib).

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
