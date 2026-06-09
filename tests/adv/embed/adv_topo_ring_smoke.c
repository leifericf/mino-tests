/*
 * adv_topo_ring_smoke.c -- 3-state ring smoke probe.
 *
 * Build a 3-node ring, post an integer at node 0, step the topology
 * 9 times (3 nodes x 3 laps), assert every node saw at least one
 * message. Doesn't check value preservation across mino_clone yet;
 * that's a Cycle B probe (adv_clone_zoo.c). This smoke just
 * exercises the wiring and verifies TSan doesn't trip.
 */

#include "../harness.h"
#include "../topology.h"
#include "mino.h"

#include <stdio.h>

static adv_verdict_t probe_ring_smoke(adv_probe_ctx_t *ctx) {
    adv_topo_t t;
    if (adv_topo_build(&t, ADV_TOPO_RING, 3) != 0) {
        adv_json_emit(ctx, "stage", "build_failed");
        return ADV_VERDICT_FAIL;
    }
    adv_json_emit(ctx, "stage", "built_ring_3");

    /* Post an int value at node 0. */
    mino_val *v = mino_int(t.nodes[0].S, 42);
    int rc = adv_topo_post(&t, 0, 1, v);
    adv_require(ctx, rc == 0);

    /* Step until quiescent or budget exhausted. The ring has 3 nodes
     * so a single 42 traveling makes one forward per step. We bound
     * total laps so a runaway loop can't lock the harness. */
    int total = 0, steps = 0;
    for (; steps < 30; steps++) {
        int f = adv_topo_step(&t);
        total += f;
        if (f == 0) break;
    }
    adv_json_emit_i(ctx, "steps", steps);
    adv_json_emit_i(ctx, "forwards", total);

    int processed[ADV_TOPO_MAX_NODES] = {0};
    adv_topo_processed(&t, processed);
    for (int i = 0; i < 3; i++) {
        char k[32];
        snprintf(k, sizeof(k), "node_%d_processed", i);
        adv_json_emit_i(ctx, k, processed[i]);
    }
    adv_require(ctx, processed[0] >= 1);
    adv_require(ctx, processed[1] >= 1);
    adv_require(ctx, processed[2] >= 1);

    adv_topo_destroy(&t);
    return ADV_VERDICT_PASS;
}

ADV_PROBE_REGISTER("topo_ring_smoke", ADV_CAT_POOL, 5000, 1,
                   probe_ring_smoke);
