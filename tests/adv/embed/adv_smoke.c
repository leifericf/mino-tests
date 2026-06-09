/*
 * adv_smoke.c -- first smoke probe.
 *
 * Asserts mino_state_new + mino_state_free round-trip with no leak
 * under ASan. Exists so the harness has a probe to dispatch from day
 * one; subsequent embed probes (clone_zoo, pool_topology, stm_mix,
 * fault_replay) follow the same shape.
 */

#include "../harness.h"
#include "mino.h"

#include <stdio.h>

static adv_verdict_t probe_state_roundtrip(adv_probe_ctx_t *ctx) {
    mino_state *S = mino_state_new();
    adv_require(ctx, S != NULL);
    if (S) {
        adv_json_emit(ctx, "stage", "state_new_ok");
        mino_state_free(S);
        adv_json_emit(ctx, "stage", "state_free_ok");
    }
    return ADV_VERDICT_PASS;
}

ADV_PROBE_REGISTER("smoke_state_roundtrip", ADV_CAT_OTHER, 500, 1,
                   probe_state_roundtrip);
