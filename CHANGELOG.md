# Changelog

## v0.2.4 — Cycle A Close: Script-Side Probe Battery T1-T11

All eleven script-side adversarial probes land at `tests/adv/script/`.
Each industrialises one bug category from the eleven defects the
v0.252.1/.2/.3 adversarial passes uncovered, plus three new probe
categories for memory x JIT, GC invariants, and concurrency primitives.

| # | Category | File | Anchor |
|---|---|---|---|
| T1 | Diag state leak across loads | `diag_carry.clj` | reader_col bug |
| T2 | Cross-mode error-shape preservation | `mode_err_shape.clj` | ex-info loss |
| T3 | REPL multi-form snippet rendering | `repl_snippets.clj` | snippet blank |
| T4 | CLI flag / env parsing parity | `cli_parity.clj` | casing asymmetry |
| T5 | mino vs mino-lean parity | `lean_parity.clj` | help/version |
| T6 | Bounded recursion / nesting depth | `depth_cliff.clj` | reader SIGSEGV |
| T7 | Per-form internal-buffer caps | `buffer_caps.clj` | GC_SAVE_MAX |
| T8 | Closure capture under TCO + JIT | `closure_tco_jit.clj` | TCO env reuse |
| T9 | Concurrency primitive deadlock | `conc_deadlock.clj` | promise+dotimes |
| T10 | Memory pressure x JIT compile | `mem_jit.clj` | (new) |
| T11 | GC live=reachable invariant | `gc_invariant.clj` | (new) |

End-to-end: `mino tests/adv/runner.clj --seed 0 --mode smoke`
emits 30+ verdict lines (some probes assert multiple invariants)
and the summary `{:total 11 :passed 11 :failed 0 :seed 0 :mode "smoke"}`.

T8 surfaced a real mino bug during the build: loop+recur inside a
defn body has all closures capturing iteration-0's value, even
though the same code at the top level works correctly. The v0.252.3
self-tail-call fix didn't reach the BC compile path for loop bodies.
Logged in ../mino/.local/BUGS.md as a candidate for a focused fix
cycle. The probe stays as the regression that will catch the fix
when it lands.

T9's `with-timeout` helper also surfaced a separate cross-thread
visibility bug in `realized?` on promises (same BUGS.md). Worked
around by relying on the runner's wall-clock budget instead of
in-probe timeouts.

Mino-specific probe-side adaptations:

  - `catch` binding (mino doesn't take `Throwable`)
  - `clojure.string/includes?` instead of `.contains` interop
  - `sh` returns `{:exit N :out S}` (no `:err`); mino's CLI prints
    errors to stdout in -e mode
  - `realized?` cross-thread limitation (see above)

## v0.2.3 — Migration In: Borderline E2E

Fourth migration batch from mino v0.253.3. The five borderline
tests land:

  - `migrated/creative_test.clj` -- closure combo, script-style
  - `migrated/doc_examples_test.clj` -- 427 lines of doc-matched
    examples. A missing clojure.string :refer chain (for
    starts-with? / ends-with? / includes?) is made explicit
    here -- the original implicitly relied on a sibling test
    file having pulled them in via :refer :all.
  - `migrated/bc_jit_deopt_test.clj` -- cross-runtime JIT deopt
  - `migrated/ns_parity_run.clj` -- multi-process parity runner
  - `migrated/spawn_stress_regression.clj` -- spawn-fleet GC stress

run_migrated.clj loads doc_examples / bc_jit_deopt /
spawn_stress; creative_test runs as a script (no deftest) and
ns_parity_run is its own runner -- both reachable directly.

Migrated suite: 483 tests / 3397 assertions, all green.

## v0.2.2 — Migration In: C-Side Embed Harnesses

Third migration batch from mino v0.253.2. The multi-state, STM,
and capability C-side embed harnesses land under
`tests/migrated_embed/`. They share the same build gap as the
new C-side adversarial probes — link against mino's runtime
needs a libmino.a shim that lands in Cycle B v0.3.0. Until
then, the source is pinned here against the v0.252.x mino API.

Files imported:

  - `migrated_embed/embed_multi_state.c` -- 16 mino_state_t x
    16 pthreads, asserts the embedding API is safe under the
    one-state-per-thread contract.
  - `migrated_embed/embed_stm_test.c`    -- STM Layer 2a smoke
    (mino_tx_run, alter_c, commute_c, ensure, watches).
  - `migrated_embed/embed_caps_test.c`   -- capability-gated
    install surface: minimal, selective caps, install_all,
    MNS002 diagnostics.

## v0.2.1 — Migration In: Fuzz / GC Stress / Fault Injection

Second migration batch from mino v0.253.1.

Files imported:

  - `migrated/reader_fuzz_test.clj`
  - `migrated/gc_generational_test.clj`,
    `migrated/gc_incremental_test.clj`
  - `migrated/regression_hamt_str_churn.clj`
  - `migrated/fault_inject_test.clj`

Promoted to top-level runners (run via task, not via run_migrated):

  - `tests/run_fault_inject.clj` -- requires the test file
    + invokes `(run-tests-and-exit)`.
  - `tests/run_gc_stress.clj` -- standalone deftest file (no
    intermediate require chain) for use under MINO_GC_STRESS=1.

New tasks in mino.edn:

  - `test-migrated`     -- runs run_migrated.clj
  - `test-fault-inject` -- runs run_fault_inject.clj
  - `test-gc-stress`    -- runs run_gc_stress.clj under
    MINO_GC_STRESS=1

Verified: `test-migrated` reports 384 tests / 3204 assertions,
`test-fault-inject` reports 5 / 5, `test-gc-stress` reports
6 / 17. All green.

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
