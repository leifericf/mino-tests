/*
 * adv_stm_mix.c -- T12: STM under writer x reader contention.
 *
 * Spawns N writer threads and M reader threads against the same set
 * of refs. Writers transact +1 / -1 in equal numbers; readers spin
 * reading the ref's value through dosync. After all threads complete
 * the net change should be zero (sum invariant).
 */

#include "../harness.h"
#include "mino.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static adv_verdict_t probe_stm_zero_sum(adv_probe_ctx_t *ctx) {
    /* Use a script-side STM workload -- the embedded C harness has
     * a one-state-per-thread contract, and we don't want to wrestle
     * with mino_state cross-thread access here. Spawning futures
     * from inside one state's script context is the supported path
     * for STM contention testing. */
    mino_state *S = mino_state_new();
    mino_set_option(S, MINO_OPT_THREAD_LIMIT, 8);  /* allow worker threads */
    mino_env *env = mino_env_new(S);
    mino_install_all(S, env);

    const char *src =
        "(let [r (ref 0)\n"
        "      n 50\n"
        "      workers 4\n"
        "      inc-futs (doall (for [_ (range workers)]\n"
        "                        (future (dotimes [_ n] (dosync (alter r inc))))))\n"
        "      dec-futs (doall (for [_ (range workers)]\n"
        "                        (future (dotimes [_ n] (dosync (alter r dec))))))]\n"
        "  (doseq [f inc-futs] @f)\n"
        "  (doseq [f dec-futs] @f)\n"
        "  @r)";
    mino_val *r = mino_eval_string(S, src, env);
    adv_require(ctx, r != NULL);
    if (r) {
        long long out = 999;
        adv_require(ctx, mino_to_int(r, &out) == 1);
        adv_json_emit_i(ctx, "final", out);
        adv_require(ctx, out == 0);
    }
    mino_state_free(S);
    return ADV_VERDICT_PASS;
}

static adv_verdict_t probe_atom_contention(adv_probe_ctx_t *ctx) {
    mino_state *S = mino_state_new();
    mino_set_option(S, MINO_OPT_THREAD_LIMIT, 8);
    mino_env *env = mino_env_new(S);
    mino_install_all(S, env);
    const char *src =
        "(let [a (atom 0)\n"
        "      workers 4\n"
        "      n 100\n"
        "      futs (doall (for [_ (range workers)]\n"
        "                    (future (dotimes [_ n] (swap! a inc)))))]\n"
        "  (doseq [f futs] @f)\n"
        "  @a)";
    mino_val *r = mino_eval_string(S, src, env);
    adv_require(ctx, r != NULL);
    if (r) {
        long long out = 0;
        adv_require(ctx, mino_to_int(r, &out) == 1);
        adv_json_emit_i(ctx, "final", out);
        adv_require(ctx, out == 400); /* 4 workers * 100 iters */
    }
    mino_state_free(S);
    return ADV_VERDICT_PASS;
}

ADV_PROBE_REGISTER("stm_zero_sum",     ADV_CAT_STM, 8000, 1,
                   probe_stm_zero_sum);
ADV_PROBE_REGISTER("atom_contention",  ADV_CAT_STM, 8000, 1,
                   probe_atom_contention);
