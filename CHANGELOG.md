# Changelog

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
