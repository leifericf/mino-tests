/*
 * adv_pool_topology.c -- T13: cross-state pool topologies.
 *
 * Drives the ring / star / mesh shapes from topology.{h,c} under a
 * shared host thread pool registered against each node's state. The
 * pool's submit_fn delegates to pthread_create directly; mino calls
 * it for each `(future ...)`. The probe doesn't exercise futures
 * end-to-end -- that's the STM mix probe -- it just verifies the
 * topology infrastructure stays sane under repeated post/step
 * cycles.
 */

#include "../harness.h"
#include "../topology.h"
#include "mino.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>

/* Trivial pool that spawns a detached thread per submit. */
static int trivial_submit(struct mino_thread_pool *pool,
                          void (*work)(void *), void *ctx) {
    (void)pool;
    pthread_t t;
    typedef struct { void (*work)(void *); void *ctx; } work_arg_t;
    work_arg_t *arg = (work_arg_t *)malloc(sizeof(*arg));
    if (!arg) return 1;
    arg->work = work; arg->ctx = ctx;
    if (pthread_create(&t, NULL, (void *(*)(void *))(void (*)(void *))work, ctx) != 0) {
        free(arg);
        return 2;
    }
    pthread_detach(t);
    free(arg);
    return 0;
}

static adv_verdict_t probe_ring_long(adv_probe_ctx_t *ctx) {
    adv_topo_t t;
    if (adv_topo_build(&t, ADV_TOPO_RING, 5) != 0) {
        adv_json_emit(ctx, "stage", "build_failed");
        return ADV_VERDICT_FAIL;
    }
    /* Post 10 distinct ints to node 0, all traveling around the ring. */
    for (int i = 0; i < 10; i++) {
        mino_val_t *v = mino_int(t.nodes[0].S, i);
        adv_require(ctx, adv_topo_post(&t, 0, 1, v) == 0);
    }
    /* Step until quiescent. */
    int total = 0;
    for (int s = 0; s < 200 && (s == 0 || total > 0); s++) {
        int f = adv_topo_step(&t);
        total = f;
    }
    int processed[ADV_TOPO_MAX_NODES] = {0};
    adv_topo_processed(&t, processed);
    for (int i = 0; i < 5; i++) {
        char k[32];
        snprintf(k, sizeof(k), "node_%d_processed", i);
        adv_json_emit_i(ctx, k, processed[i]);
        adv_require(ctx, processed[i] >= 1);
    }
    adv_topo_destroy(&t);
    return ADV_VERDICT_PASS;
}

static adv_verdict_t probe_star(adv_probe_ctx_t *ctx) {
    adv_topo_t t;
    if (adv_topo_build(&t, ADV_TOPO_STAR, 4) != 0)
        return ADV_VERDICT_FAIL;
    /* Each spoke posts a message to the hub; hub forwards to next spoke. */
    for (int i = 1; i < 4; i++) {
        mino_val_t *v = mino_int(t.nodes[i].S, i * 100);
        adv_require(ctx, adv_topo_post(&t, i, 0, v) == 0);
    }
    for (int s = 0; s < 30; s++) {
        int f = adv_topo_step(&t);
        if (f == 0) break;
    }
    int processed[ADV_TOPO_MAX_NODES] = {0};
    adv_topo_processed(&t, processed);
    /* Hub processed at least 3 (one per spoke). */
    adv_require(ctx, processed[0] >= 3);
    adv_topo_destroy(&t);
    return ADV_VERDICT_PASS;
}

static adv_verdict_t probe_pool_registered(adv_probe_ctx_t *ctx) {
    /* Register a host thread pool against a state; mino accepts it. */
    mino_state_t *S = mino_state_new();
    static mino_thread_pool_t pool = {.submit_fn = trivial_submit,
                                      .user_data = NULL};
    mino_set_thread_pool(S, &pool);
    adv_json_emit(ctx, "stage", "pool_registered");
    /* Unregister (NULL pool) -- must not crash. */
    mino_set_thread_pool(S, NULL);
    adv_json_emit(ctx, "stage", "pool_unregistered");
    mino_state_free(S);
    return ADV_VERDICT_PASS;
}

ADV_PROBE_REGISTER("pool_ring_long",     ADV_CAT_POOL, 5000, 1,
                   probe_ring_long);
ADV_PROBE_REGISTER("pool_star",          ADV_CAT_POOL, 5000, 1,
                   probe_star);
ADV_PROBE_REGISTER("pool_registered",    ADV_CAT_POOL, 2000, 1,
                   probe_pool_registered);
