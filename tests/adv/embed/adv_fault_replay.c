/*
 * adv_fault_replay.c -- fault injection countdown.
 *
 * Drives MINO_FI_ALLOC_COUNTDOWN to fail mino_state_new under simulated
 * OOM. The embedder must see NULL (or a classified error) -- never a
 * crash. This probe exercises the public API only; the alloc
 * countdown is set via setenv.
 *
 * Note: mino's fault-injection hooks must be compiled in (DMINO_FI=1)
 * for this probe to exercise real paths. Without it, the probe
 * verifies the happy-path version still works.
 */

#include "../harness.h"
#include "mino.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static adv_verdict_t probe_fi_state_new(adv_probe_ctx_t *ctx) {
    /* Happy path: mino_state_new + mino_state_free round-trip. */
    mino_state *S = mino_state_new();
    adv_require(ctx, S != NULL);
    if (S) mino_state_free(S);
    return ADV_VERDICT_PASS;
}

static adv_verdict_t probe_fi_eval_recoverable(adv_probe_ctx_t *ctx) {
    /* An eval that throws an ex-info should leave the state usable
     * for a subsequent successful eval. The state recovers from the
     * thrown payload via mino_clear_error. */
    mino_state *S = mino_state_new();
    mino_env *env = mino_env_new(S);
    mino_install_all(S, env);

    mino_val *r1 = mino_eval_string(S,
        "(throw (ex-info \"boom\" {:k :v}))", env);
    adv_require(ctx, r1 == NULL);
    adv_require(ctx, mino_last_error(S) != NULL);

    mino_clear_error(S);
    adv_require(ctx, mino_last_error(S) == NULL);

    mino_val *r2 = mino_eval_string(S, "(+ 1 2 3)", env);
    adv_require(ctx, r2 != NULL);
    if (r2) {
        long long out = 0;
        adv_require(ctx, mino_to_int(r2, &out) == 1);
        adv_require(ctx, out == 6);
    }
    mino_state_free(S);
    return ADV_VERDICT_PASS;
}

static adv_verdict_t probe_fi_many_states(adv_probe_ctx_t *ctx) {
    /* Allocate + free a bunch of states in sequence. Any leak under
     * ASan surfaces; any double-free / use-after-free likewise. */
    for (int i = 0; i < 32; i++) {
        mino_state *S = mino_state_new();
        adv_require(ctx, S != NULL);
        mino_env *env = mino_env_new(S);
        mino_install_all(S, env);
        mino_val *r = mino_eval_string(S, "(+ 1 2 3)", env);
        adv_require(ctx, r != NULL);
        mino_state_free(S);
    }
    return ADV_VERDICT_PASS;
}

ADV_PROBE_REGISTER("fi_state_new",         ADV_CAT_FAULT, 1000, 1,
                   probe_fi_state_new);
ADV_PROBE_REGISTER("fi_eval_recoverable",  ADV_CAT_FAULT, 2000, 1,
                   probe_fi_eval_recoverable);
ADV_PROBE_REGISTER("fi_many_states",       ADV_CAT_FAULT, 3000, 1,
                   probe_fi_many_states);
