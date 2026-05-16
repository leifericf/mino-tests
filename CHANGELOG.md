# Changelog

## v0.7.5 — Differential-Test Tasks + CI Wiring (Cycle Close)

Last release of the differential-test cycle. Surfaces the diff
machinery as standalone tasks and wires it into CI:

* `mino.edn` registers two new tasks: `diff-test` (smoke, fixed
  seed=0, diff probes only, ~1s) and `diff-test-soak` (soak, random
  seed, 1000 random + 200/category, ~10s on the dev host, up to
  60min budget in CI).
* `runner.clj` gains a `--only <substring>` flag that filters
  probes by path; `diff-test` uses `--only diff_` so the T1..T11
  battery is skipped.
* `.github/workflows/ci.yml` runs the diff smoke after the
  Adversarial T1..T11 step with a 3-min wall-clock cap. On failure
  the auto-captured regression files under `tests/adv/regressions/
  diff-*` are uploaded as an `actions/upload-artifact@v4` artifact
  for offline triage.
* `.github/workflows/ci-nightly.yml` runs the diff soak with a
  60-min wall-clock cap, random seed per run, same regression
  artifact upload on failure.

mino's `release-gate` already chains into mino-tests's runner via
the v0.254.0 hook; the diff probes are part of that script-probes
list so they run automatically without further mino-side wiring.

Verified at cycle close:

* `./mino/mino task diff-test` -> 6/6 probes green in ~860 ms.
* `./mino/mino task diff-test-soak` (capped to 30s slice via
  `--max-ms 30000`) -> 1805 program executions across 1 random
  seed (42), 0 divergences, 0 regressions captured.
* Full `tests/adv/runner.clj --seed 0 --mode smoke` -> 17/17
  probes green in ~1010 ms.

## v0.7.4 — JIT-Specific Probes (`script/diff_jit_specific.clj`)

Five JIT-behavior assertions the quad-byte-id runner can't make on
its own (those compare independent processes; these probe
within-process state):

* **Warmup parity** -- a fn computed cold matches the same fn
  computed warm (5000 iterations). A JIT miscompile flips bits.
* **Deopt after redef** -- a fn heated past the JIT threshold,
  then redefined, must dispatch to the new body. Pins the
  `native_gen` / `S->ic_gen` invariant.
* **IC invalidation on global var redef** -- a fn closing over a
  global var, heated past the threshold, must see the new value
  after the var is redefined.
* **Mode-switch byte-id** -- the same fib/fact/reduce shape under
  --jit=off, --jit=on, and --jit=auto produces byte-identical
  stdout.
* **Protocol dispatch stability** -- a heated protocol-method site
  must dispatch correctly when the protocol is later extended to
  a new type.

Verified: all 5 probes green at seed 0; total smoke run climbs from
~1015 ms to ~1010 ms (the JIT probes complete in ~3 ms). 17/17
probes pass.

## v0.7.3 — Targeted Category Generators (BC Closure / Arith / Coll / Ctrl)

Four template-driven generators surface BC compile-path divergences
random gen_program is unlikely to hit on its own:

* `gen_bc_closure_shapes.clj` -- 8 templates including the v0.255.0
  anchor (loop+recur fn-capture-per-iteration inside defn) and the
  fn-returning-fn shape from outer-captures.
* `gen_bc_arith_shapes.clj` -- 8 templates pinning the v0.255.5
  bit-shift-left bigint promotion bug, plus xor/and/or chains,
  ushr, inc/dec on boxed ints, and rem/mod with negative operands.
* `gen_bc_collection_shapes.clj` -- 8 templates exercising conj,
  subvec, assoc/dissoc, get-with-default, transient-conj-persistent,
  nth+count, reduce-kv (sum is order-invariant so the printed
  witness stays deterministic), and contains? on sets.
* `gen_bc_control_shapes.clj` -- 8 templates covering ex-info
  throw/catch, cond chains, nested let/loop with shadowed names,
  try/catch around quot-div, when chains, dotimes side-effect
  collector, and if-with-try-in-each-branch.

Each generator is fronted by a `diff_bc_<category>_shapes.clj`
probe that runs N programs through the quad (20 smoke, 200 soak)
and auto-captures + shrinks any divergence into
`tests/adv/regressions/diff-<category>-<seed>-<i>.clj`.

Verified: 80 targeted programs at seed 0 all pass quad-byte-id;
new probes register in the runner; total smoke run climbs from
~840 ms to ~1015 ms (16/16 probes green).

## v0.7.2 — Witness Shrinker (`shrink.clj`)

When `diff_random` finds a quad divergence, the captured regression
now embeds a minimal witness rather than the full random program.
`shrink-divergent` runs delta-debugging-shaped form-removal followed
by a small library of textual simplifications; the final printing
form is always preserved so the program still has stdout to compare
against.

Wall-clock budget: 15s per divergence inside `diff_random` (so a
single bug doesn't blow the smoke budget); the standalone helper
defaults to 30s.

Verified:

* Non-diverging input -- shrinker returns the original unchanged.
* Synthetic divergence (a program "diverges" iff source contains
  a sentinel substring) -- shrinker drops 2 of 4 defns and keeps
  the form carrying the sentinel plus the final `(println ...)`.

## v0.7.1 — Differential Quad Runner (`script/diff_random.clj`)

Generated programs from v0.7.0 are now walked through the four
runtime shapes (auto / on / off / lean) and the `{:exit :out}` map
is required to match byte-for-byte across all four. On a mismatch,
the program source, seed, and four divergent outputs are auto-captured
to `tests/adv/regressions/diff-random-<seed>-<i>.clj` with a
self-contained re-run shim; the regression then loads on every
subsequent runner invocation and re-asserts the same quad.

Walk size: 100 programs in smoke (~830 ms locally), 1000 programs
in soak. The seed is XOR'd with a probe-specific tag so re-running
with `--replay <seed>` reproduces the same program stream regardless
of probe load order.

Verified: 1000 programs across 10 seeds (1, 2, 3, 5, 7, 11, 13, 17,
23, 31) pass quad-byte-id with zero divergences -- strong baseline
evidence that the four runtimes agree on the random-program shape
gen_program emits.

## v0.7.0 — Seeded mino Program Generator (`gen_program.clj`)

First piece of the differential-test infrastructure. `gen_program.clj`
emits seeded random pure mino programs and `edge_helpers/run-quad`
now returns `{:exit :out}` per variant so a quad-byte-id check
detects both stdout and exit-code divergences across
auto / on / off / lean.

Program shape: 1-3 (defn ...) followed by `(println EXPR)`. Bodies
combine `let`, `loop`+`recur`, `if`, `when`, `or`+`when`, two-arity
arithmetic (`+ - * quot rem mod bit-and`), comparisons, calls into
previously-defined defns, and references to params / let-locals.

Determinism constraints:

* No I/O, no concurrency, no `rand` / `time` / `read-string`.
* `quot`/`rem`/`mod` divisors are wrapped in `(if (zero? d) 1 (op a d))`
  guards at generation time.
* Loops use a literal bounded counter so they always terminate.
* All four runtimes receive byte-identical source and (for valid
  programs) must produce byte-identical `{:exit :out}` maps.

Verified: 99/100 generated programs at seed 0 pass through
`mino --jit=off` cleanly; the failing program surfaced a real
BC-compile bug logged to mino's `.local/BUGS.md` (`if`+`else`
fold-error in fn bodies). The bug fails uniformly across all four
runtimes so it is not a quad divergence -- the differential
machinery is unblocked.

## v0.6.1 — gen.clj Switches Back to xorshift64*

mino v0.255.5 fixed the BC compiler's bitwise-fast-path bigint
promotion bug that gen.clj's xorshift64* surfaced during Cycle Z.2.
With the fix landed, gen.clj reverts to the proper xorshift64*
recipe.

Verified: all 11 script-side probes still pass.

## v0.6.0 — Polish: Soak Stop-Conditions + JSON Tail

Runner gains two operational knobs and a richer summary tail:

* `--max-ms <ms>` -- hard wall-clock budget. After the deadline the
  next probe is skipped and the run summary is emitted.
* `--continue-on-fail` -- by default the runner stops at the first
  failure (so a soak doesn't waste budget on a confirmed bug);
  this flag overrides and lets every probe run.
* Summary line now includes `:elapsed <ms>` so dashboards can
  pick up the run duration without parsing the wall-clock from
  outside.

The combined effect: `mino tests/adv/runner.clj --max-ms 60000
--continue-on-fail` is the "give me everything in 60 seconds"
shape; default `mino tests/adv/runner.clj --seed 0 --mode smoke`
stays the CI-tight shape.

Verified: smoke runs in ~96ms; --max-ms 60000 leaves headroom; the
deadline-hit path is exercised by `--max-ms 1` (run terminates
after probe 1 / 2 with the deadline-hit branch taken).

## v0.4.0 — Coverage + Replay + Regression Auto-Capture

llvm-cov integration + replay-by-seed + auto-captured regression
files land.

* `adv-test-coverage` task now (a) detects clang / llvm-profdata /
  llvm-cov via xcrun -f or which; (b) builds the harness with
  `-fprofile-instr-generate -fcoverage-mapping`; (c) runs with
  LLVM_PROFILE_FILE pointing at tests/adv/coverage/mino.profraw;
  (d) merges via llvm-profdata; (e) emits HTML at
  tests/adv/coverage/html/index.html.
* runner.clj accepts `--replay <seed>` which overrides --seed.
  Re-runs are deterministic against the registered RNG state
  (gen.clj's u32 LCG); a future random-seeded soak run that flags
  a probe at seed S reproduces with `--replay S`.
* runner.clj auto-captures failing probes into
  tests/adv/regressions/<ts>-<probe-name>.clj. Each regression
  file is a 4-line script that re-loads the probe; the runner
  loads regressions/* before the live battery so a fix that
  reverts the probe surfaces immediately.

Verified: `--replay 42` reports `:seed 42 :replay true` and runs
all 11 probes; the regression directory remains empty when the
suite is green (no auto-captures triggered).

## v0.3.0 — Embed-side Probes T12-T14 + Sanitizer Trinity

The C-side adversarial probe battery + the sanitizer-trinity build
path land together. The harness now links against mino's runtime
sources directly (mino doesn't produce a libmino.a; .o files for
release, full recompile under sanitizer flags).

Embed probes (under `tests/adv/embed/`):

  - `adv_clone_zoo.c` (T14) -- mino_clone fidelity across int,
    vector, map, string, and a nested {:items [...] :meta {...}
    :tags #{...}} value. Each shape's type predicate survives the
    cross-state move.
  - `adv_pool_topology.c` (T13) -- ring(5) long-run with 10 distinct
    ints traveling around; star(4) with 3 spokes posting to hub;
    thread-pool register / unregister round-trip.
  - `adv_stm_mix.c` (T12) -- STM zero-sum under writer / dec-writer
    contention (4 workers each, 50 iters); atom contention (4
    workers, 100 swap! inc). Uses script-side futures because the
    C harness is one-state-per-thread.
  - `adv_fault_replay.c` -- mino_state_new round-trip, eval-error
    recovery (clear_error + re-eval), 32x state alloc/free leak
    test.

`build-harness` task supports four variants:

  - `:release` -- O2, links against mino's prebuilt .o files
  - `:asan`    -- AddressSanitizer + UBSan combo
  - `:ubsan`   -- UndefinedBehaviorSanitizer
  - `:tsan`    -- ThreadSanitizer

`adv-test-sanitizers` task drives all three sanitizer variants
sequentially.

Verified end-to-end:

  - 15 / 15 probes pass under :release
  - 15 / 15 pass under :asan (no diagnostics)
  - 15 / 15 pass under :ubsan (no diagnostics)
  - 15 / 15 pass under :tsan; TSan surfaces one real GC vs worker
    race that's logged in mino's `.local/BUGS.md` for a focused
    GC-concurrency fix cycle.

## v0.5.0 — CI Wiring + Dedup Audit

GitHub Actions workflows land + dedup audit closes overlap with
mino's tests.

CI workflows:

  - `.github/workflows/ci.yml` -- PR-time smoke. Checks out
    mino-tests with `submodules: recursive`, builds mino from the
    submodule, runs `tests/run_migrated.clj` + `tests/run_fault_inject.clj`
    + the adversarial T1-T11 smoke battery. Matrix: ubuntu-24.04,
    ubuntu-24.04-arm, macos-14. 15-minute budget.
  - `.github/workflows/ci-nightly.yml` -- daily heavier work:
    adversarial soak with random seed, GC stress under
    MINO_GC_STRESS=1, coverage pass (clang-only, continue-on-error
    until Cycle C wires the full coverage binding).

Cross-repo hook (lands in mino v0.254.0):

  - mino's `release-gate` composite now detects a sibling
    mino-tests clone and chains into its adversarial smoke as
    the final gate step. No hard dependency: a fresh mino clone
    without mino-tests adjacent prints "skipped satellite
    smoke" and the gate continues.

Dedup audit:

  - `tests/migrated/creative_test.clj` removed -- zero deftests,
    just a script that printed values. The probe battery's
    closure_tco_jit + buffer_caps cover the same surfaces with
    real assertions; the script-style file belongs in
    mino-examples (which already exists as a sibling repo) when
    the user revisits that.
  - `tests/migrated/ns_parity_run.clj` returned to mino -- it's
    a runner that requires `tests/ns_isolation_test`,
    `tests/ns_libs_test`, etc., which all stayed in mino. The
    runner is not standalone; mino is its home.
  - `run_migrated.clj` updated to drop both references.

Verified: `mino tests/run_migrated.clj` reports 483 / 3397, all
green; `mino tests/adv/runner.clj --seed 0 --mode smoke` reports
11 / 11 probes pass.

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
