# Mutation-testing lane image. Pins the matched (LLVM 19, mull@19)
# pair the lane's absolute-path resolvers expect, plus mino's build
# deps and babashka. This is the reproducible CI home for the nightly
# mutation job, consistent with the "use Docker Linux for the heavy /
# tool-dependent lanes" doctrine the sanitizer and coverage lanes
# already follow. Dev iteration can still use host Homebrew (llvm@19 +
# mull@19); CI runs this container so a `brew upgrade` never drifts it.
#
# WHY THE PIN IS LOAD-BEARING: Mull injects its mutants at the LLVM IR
# level through a clang pass plugin whose ABI is version-locked. A
# clang whose LLVM major differs from mull's would silently mutate a
# phantom program. The image self-verifies the matched pair via
# `mutation-doctor` at build time (below) and the lane re-asserts it
# before every build.
#
# Base is ubuntu-24.04 to match the on-push CI runner label, so the
# mutated mino_mut is built by the same libc/toolchain family the
# smoke suite runs against.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# mino builds with `make` + `cc`; the lane recompiles the critical-dir
# TUs through the matched LLVM clang. git is needed for the submodule
# checkout, curl/wget/gnupg for the two apt repos, ca-certificates for
# TLS to Cloudsmith + apt.llvm.org, python3 for the E2E fixture peers
# the broader suite uses, and the usual build toolchain for the link.
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        curl \
        wget \
        gnupg \
        lsb-release \
        software-properties-common \
        git \
        make \
        pkg-config \
        python3 \
        unzip \
    && rm -rf /var/lib/apt/lists/*

# --- LLVM 19 (matched clang for the pass plugin) --------------------
# apt.llvm.org's automatic installer. `all` pulls the full toolchain so
# the prefix at /usr/lib/llvm-19 carries clang + the shared libs mull's
# frontend links against. The lane resolves clang by absolute path
# (/usr/lib/llvm-19/bin/clang), never a bare `clang` on PATH.
RUN wget -q https://apt.llvm.org/llvm.sh \
    && chmod +x llvm.sh \
    && ./llvm.sh 19 all \
    && rm -f llvm.sh \
    && rm -rf /var/lib/apt/lists/*

# --- mull@19 0.34.0 (matched runner + IR frontend) ------------------
# mull ships through its own Cloudsmith apt repo (NOT Homebrew on
# Linux). The setup script installs the signing key and source list;
# then the versioned package `mull-19` lands mull-runner-19 in /usr/bin
# and the mull-ir-frontend-19 pass plugin in /usr/lib.
RUN curl -1sLf \
        'https://dl.cloudsmith.io/public/mull-project/mull-stable/setup.deb.sh' \
        | bash \
    && apt-get update \
    && apt-get install -y --no-install-recommends mull-19 \
    && rm -rf /var/lib/apt/lists/*

# --- babashka -------------------------------------------------------
# The lane's tasks run under the mino binary (`./mino/mino task <name>`)
# because impl.clj uses mino's own `sh!` / `getenv` primitives, not bb
# builtins. babashka is installed anyway for the repo's other bb-driven
# scripting and to keep the image consistent with the wider tool set.
RUN curl -sSL \
        https://raw.githubusercontent.com/babashka/babashka/master/install \
        | bash

# --- Toolchain pin the resolvers key off (absolute paths) -----------
# lib/mino_tests/tasks/impl.clj resolves:
#   mut-clang        = ${MINO_LLVM19_PREFIX}/bin/clang
#   mull-runner      = ${MINO_MULL_PREFIX}/bin/mull-runner-19
#   mull-ir-frontend = ${MINO_MULL_PREFIX}/lib/mull-ir-frontend-19
# so the prefixes below make the Homebrew-shaped defaults resolve to
# the apt layout without touching the lane code.
ENV MINO_LLVM19_PREFIX=/usr/lib/llvm-19
ENV MINO_MULL_PREFIX=/usr

# The apt package may ship the pass plugin with a `.so` suffix on some
# builds; the resolver expects the plain name. Normalise it so the
# absolute path the lane asserts always exists. Fail loudly if neither
# form is present rather than defer a wrong path into the mutation run.
RUN if [ -e /usr/lib/mull-ir-frontend-19 ]; then \
        echo "mull-ir-frontend-19 present at expected path"; \
    elif [ -e /usr/lib/mull-ir-frontend-19.so ]; then \
        ln -s /usr/lib/mull-ir-frontend-19.so /usr/lib/mull-ir-frontend-19; \
    else \
        echo "ERROR: mull-ir-frontend-19 plugin not found under /usr/lib" >&2; \
        dpkg -L mull-19 | grep -i frontend >&2 || true; \
        exit 1; \
    fi \
    && test -x /usr/bin/mull-runner-19 \
    && test -x /usr/lib/llvm-19/bin/clang

WORKDIR /work

# BUILD-TIME self-verify of the matched (LLVM 19 == mull LLVM 19) pair.
# This asserts exactly the invariant the lane's `mutation-doctor` task
# asserts (both tools present, their LLVM majors equal), but at the
# shell level so it needs no bootstrapped mino (the submodule is mounted
# only at run time). A bad pin fails `docker build`, never a nightly
# run. The lane re-runs the real `mutation-doctor` task at entry (below)
# and again inside `mutation-build`, so the pair is checked three times.
RUN set -e; \
    clang_ver="$(/usr/lib/llvm-19/bin/clang --version)"; \
    runner_ver="$(/usr/bin/mull-runner-19 --version)"; \
    clang_major="$(printf '%s' "$clang_ver" | grep -oE 'clang version [0-9]+' | grep -oE '[0-9]+' | head -1)"; \
    mull_major="$(printf '%s' "$runner_ver" | grep -oE 'LLVM:? [0-9]+' | grep -oE '[0-9]+' | head -1)"; \
    echo "clang LLVM major: ${clang_major}"; \
    echo "mull  LLVM major: ${mull_major}"; \
    if [ -z "$clang_major" ] || [ -z "$mull_major" ]; then \
        echo "ERROR: could not parse an LLVM major from --version" >&2; exit 1; \
    fi; \
    if [ "$clang_major" != "$mull_major" ]; then \
        echo "ERROR: LLVM major mismatch: clang ${clang_major} != mull ${mull_major}" >&2; \
        exit 1; \
    fi; \
    if [ "$clang_major" != "19" ]; then \
        echo "ERROR: pinned pair drifted off LLVM 19 (got ${clang_major})" >&2; \
        exit 1; \
    fi; \
    echo "mutation image: matched pair OK (LLVM ${clang_major})"

# At container run time CI mounts the full mino-tests checkout (with the
# mino submodule) over /work. The entrypoint bootstraps nothing on its
# own; CI builds mino then drives the lane via `./mino/mino task`, whose
# first mutation step re-runs the real `mutation-doctor` task against
# the same MINO_LLVM19_PREFIX / MINO_MULL_PREFIX set here.
CMD ["bash"]
