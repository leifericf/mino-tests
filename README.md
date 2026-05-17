# mino-tests

Adversarial / E2E / soak test battery for [mino](https://github.com/leifericf/mino).

This is the satellite test suite — heavy, multi-runtime, concurrency-soak,
fuzz, sanitizer-trinity, coverage, and adversarial probes that don't
belong in the main repo's unit-test set. The two repos pair up:

| Repo | Holds |
|---|---|
| `mino` (`tests/`) | Language-semantics unit tests — one primitive or special form at a time, sub-second, single-runtime. |
| `mino-tests` (this) | Anything multi-runtime, concurrency-heavy, fuzz, soak, sanitizer-trinity, coverage, adversarial. |

When in doubt about where a test belongs: does it exercise the runtime in
isolation, or does it cross runtimes / take >100ms / depend on external
tooling? The former stays in `mino`; the latter belongs here.

## Layout

```
mino-tests/
  mino/                       -- mino as git submodule (pinned)
  mino.edn                    -- mino-tests project manifest
  lib/mino_tests/tasks.clj    -- mino task entry points
  tests/adv/
    harness.{c,h}             -- C99 probe harness
    topology.{c,h}            -- ring/star/mesh cross-state helpers
    edge_helpers.clj          -- script-side helpers (with-seed, run-quad)
    gen.clj                   -- seeded RNG, shape generators
    invariants.clj            -- invariant fns shared across probes
    runner.clj                -- aggregates script+regression probes
    script/*.clj              -- T1..T11 script-side probes
    embed/*.c                 -- T12..T14 C harnesses
    regressions/*.clj         -- auto-captured failing seeds
    coverage/                 -- llvm-cov reports (when run)
  tests/migrated/             -- tests moved out of mino (Cycle Y)
  .github/workflows/          -- ci.yml (smoke), ci-nightly.yml (soak)
```

## Quickstart

```
git clone --recurse-submodules git@github.com:leifericf/mino-tests.git
cd mino-tests
cd mino && make && cd ..
./mino/mino task adv-test           -- smoke (under 30s, fixed seed=0)
./mino/mino task adv-test-soak      -- full soak (random seed, ~minutes)
./mino/mino task adv-test-coverage  -- llvm-cov build + html report (clang only)
./mino/mino task adv-test-sanitizers  -- ASan + UBSan + TSan trinity
```

For local development the submodule may lag behind a private mino branch.
Set `MINO_BIN=/path/to/your/local/mino` to override the binary used by the
runner.

## Tasks

| Task | Purpose |
|---|---|
| `adv-test` | Smoke battery, fixed seed=0, under 30s. CI uses this. |
| `adv-test-soak` | Full soak, random seed, several minutes. Nightly CI uses this. |
| `adv-test-coverage` | Clang-only llvm-cov pass. Writes `tests/adv/coverage/report.html`. |
| `adv-test-sanitizers` | Triple sanitizer pass (ASan + UBSan + TSan). |
| `build-cov` | Build `mino_cov` instrumented binary. |
| `bump-mino` | Move the `mino` submodule to a newer tag and lock it. |
| `clojuredocs-refresh` | Re-download the ClojureDocs example export and rebuild the diff-probe fixture. Dev-host only; needs `bb` on PATH. |

## Boundary principle

```
single primitive / special form ........ mino/tests/
sub-second, single-runtime ............. mino/tests/
multi-runtime / cross-state ............ mino-tests/
takes >100ms or many iterations ........ mino-tests/
fuzz / random / soak ................... mino-tests/
sanitizer-trinity ...................... mino-tests/
coverage instrumented .................. mino-tests/
adversarial whitebox probe ............. mino-tests/
```

## Standing rules

- The `mino` submodule pin must be a public tag or sha. Local-only mino
  tags do not roll into this repo's CI until they're pushed.
- Auto-captured regressions land in `tests/adv/regressions/` with seed +
  minimized form so they reproduce deterministically.
- No bash scripts in this repo — tooling lives in `lib/mino_tests/tasks.clj`
  as mino tasks. Configuration (Dockerfiles, workflow YAML) is allowed.
- No plan / status / BUGS files in git — those live in `.local/`.

## ClojureDocs differential probe

`tests/adv/script/diff_clojuredocs.clj` runs user-written examples
from clojuredocs.org through mino and asserts byte-identical printed
output against a recorded ground truth.

Ground truth is captured at fixture-build time (`clojuredocs-refresh`
task) by spawning `bb` against each example: the documented `;;=>`
strings on clojuredocs.org are noisy (stale, dropped string quotes,
Clojure version skew), but bb's actual printed output is what a user
pasting the snippet into the JVM REPL sees today. mino has to match.

The fixture lives at `tests/adv/fixtures/clojuredocs-tuples.edn`
(~360 KB, ~1100 examples covering `clojure.core`, `clojure.string`,
`clojure.set`, `clojure.walk`, `clojure.zip`, `clojure.template`,
`clojure.edn`, and `clojure.spec.alpha`). Examples that exercise
Java interop, side effects, REPL state, or async are pre-triaged
and never reach the probe.

`tests/adv/fixtures/clojuredocs_allowlist.edn` keys intentional
divergences (named `unchecked-*` opt-ins, mino design choices that
won't match JVM semantics). Allowlisted misses are skip, not fail.

The probe is automatically included in `diff-test` / `diff-test-soak`
since its filename matches the `diff_` filter; nightly CI runs the
soak form via `diff-test-soak` without any extra wiring.
