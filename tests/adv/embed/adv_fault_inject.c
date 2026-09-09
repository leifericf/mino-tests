/*
 * adv_fault_inject.c -- deterministic OOM fault-injection probes.
 *
 * Whitebox probes that drive mino's private allocation-fault hooks
 * (mino_set_fail_alloc_at / mino_set_fail_raw_at) to verify that an
 * allocation failure in any subsystem produces a catchable error --
 * never a crash or an abort -- and that the state stays usable
 * afterward. These hooks and mino_env_new_default live in the private
 * companion header, so this probe links against mino's internals
 * rather than the public embed surface.
 */

#include "../harness.h"
#include "mino_internal.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>

/* Find the lowest allocation count N such that eval_string with
 * fail-at-N returns NULL (OOM). Returns N, or 0 if no failure was
 * triggered within the first max_probes allocations. */
static long find_fail_point(mino_state *S, mino_env *env,
                            const char *expr, long max_probes) {
    long n;
    for (n = 1; n <= max_probes; n++) {
        mino_set_fail_alloc_at(S, n);
        if (mino_eval_string(S, expr, env) == NULL) {
            mino_set_fail_alloc_at(S, 0);
            return n;
        }
        mino_set_fail_alloc_at(S, 0);
    }
    return 0;
}

/* OOM during map construction is recoverable. */
static adv_verdict_t probe_map_oom(adv_probe_ctx_t *ctx) {
    mino_state *S = mino_state_new();
    mino_env *env = mino_env_new_default(S);
    long n;

    n = find_fail_point(S, env, "(hash-map :a 1 :b 2 :c 3 :d 4)", 200);
    adv_require(ctx, n > 0);

    /* The error message must be set on the injected failure. */
    mino_set_fail_alloc_at(S, n);
    adv_require(ctx,
        mino_eval_string(S, "(hash-map :a 1 :b 2 :c 3 :d 4)", env) == NULL);
    adv_require(ctx, mino_last_error(S) != NULL);
    mino_set_fail_alloc_at(S, 0);

    /* Recovery: a subsequent eval succeeds. */
    adv_require(ctx, mino_eval_string(S, "(+ 1 1)", env) != NULL);

    mino_env_free(S, env);
    mino_state_free(S);
    return ADV_VERDICT_PASS;
}

/* OOM during vector construction is recoverable. */
static adv_verdict_t probe_vector_oom(adv_probe_ctx_t *ctx) {
    mino_state *S = mino_state_new();
    mino_env *env = mino_env_new_default(S);
    long n;

    n = find_fail_point(S, env, "(into [] (range 100))", 500);
    adv_require(ctx, n > 0);

    mino_set_fail_alloc_at(S, n);
    adv_require(ctx,
        mino_eval_string(S, "(into [] (range 100))", env) == NULL);
    adv_require(ctx, mino_last_error(S) != NULL);
    mino_set_fail_alloc_at(S, 0);

    adv_require(ctx, mino_eval_string(S, "(+ 1 1)", env) != NULL);

    mino_env_free(S, env);
    mino_state_free(S);
    return ADV_VERDICT_PASS;
}

/* OOM during a binding form is recoverable. */
static adv_verdict_t probe_binding_oom(adv_probe_ctx_t *ctx) {
    mino_state *S = mino_state_new();
    mino_env *env = mino_env_new_default(S);
    long n;

    n = find_fail_point(S, env,
        "(let [a 1 b 2 c 3 d 4 e 5] (+ a b c d e))", 200);
    adv_require(ctx, n > 0);

    mino_set_fail_alloc_at(S, n);
    adv_require(ctx, mino_eval_string(S,
        "(let [a 1 b 2 c 3 d 4 e 5] (+ a b c d e))", env) == NULL);
    mino_set_fail_alloc_at(S, 0);

    adv_require(ctx, mino_eval_string(S, "(+ 1 1)", env) != NULL);

    mino_env_free(S, env);
    mino_state_free(S);
    return ADV_VERDICT_PASS;
}

/* OOM during regex compile is recoverable. */
static adv_verdict_t probe_regex_oom(adv_probe_ctx_t *ctx) {
    mino_state *S = mino_state_new();
    mino_env *env = mino_env_new_default(S);
    long n;

    n = find_fail_point(S, env, "(re-find #\"[a-z]+\" \"hello\")", 200);
    adv_require(ctx, n > 0);

    mino_set_fail_alloc_at(S, n);
    adv_require(ctx,
        mino_eval_string(S, "(re-find #\"[a-z]+\" \"hello\")", env) == NULL);
    mino_set_fail_alloc_at(S, 0);

    adv_require(ctx, mino_eval_string(S, "(+ 1 1)", env) != NULL);

    mino_env_free(S, env);
    mino_state_free(S);
    return ADV_VERDICT_PASS;
}

/* OOM is catchable via try/catch in mino code. */
static adv_verdict_t probe_oom_catchable(adv_probe_ctx_t *ctx) {
    mino_state *S = mino_state_new();
    mino_env *env = mino_env_new_default(S);
    long n;
    mino_val *r;

    n = find_fail_point(S, env, "(hash-map :a 1 :b 2 :c 3 :d 4)", 200);
    adv_require(ctx, n > 0);

    /* Wrapped in try/catch: returns :oom instead of crashing. The
     * try/catch itself may need allocations that also fail, so accept
     * either a caught keyword or a NULL return. */
    mino_set_fail_alloc_at(S, n);
    r = mino_eval_string(S,
        "(try (hash-map :a 1 :b 2 :c 3 :d 4) (catch e :oom))", env);
    mino_set_fail_alloc_at(S, 0);
    if (r != NULL) {
        adv_require(ctx, mino_is_keyword(r));
    }

    /* Either way, the state must be recoverable. */
    adv_require(ctx, mino_eval_string(S, "(+ 1 1)", env) != NULL);

    mino_env_free(S, env);
    mino_state_free(S);
    return ADV_VERDICT_PASS;
}

/* OOM during a vector clone via raw fault injection is recoverable. */
static adv_verdict_t probe_clone_vector_oom(adv_probe_ctx_t *ctx) {
    mino_state *src = mino_state_new();
    mino_state *dst = mino_state_new();
    mino_env *se = mino_env_new_default(src);
    mino_env *de = mino_env_new_default(dst);
    long n;

    mino_val *v = mino_eval_string(src, "[1 2 3 4 5]", se);
    adv_require(ctx, v != NULL);

    /* Find a failure point in the raw allocation path. */
    for (n = 1; n <= 20; n++) {
        mino_set_fail_raw_at(dst, n);
        if (mino_clone(dst, src, v) == NULL) {
            mino_set_fail_raw_at(dst, 0);
            break;
        }
        mino_set_fail_raw_at(dst, 0);
    }
    adv_require(ctx, n <= 20);

    /* Recovery: the clone succeeds after clearing the injection. */
    adv_require(ctx, mino_clone(dst, src, v) != NULL);

    mino_env_free(src, se);
    mino_env_free(dst, de);
    mino_state_free(src);
    mino_state_free(dst);
    return ADV_VERDICT_PASS;
}

/* OOM during a map clone via raw fault injection is recoverable. */
static adv_verdict_t probe_clone_map_oom(adv_probe_ctx_t *ctx) {
    mino_state *src = mino_state_new();
    mino_state *dst = mino_state_new();
    mino_env *se = mino_env_new_default(src);
    mino_env *de = mino_env_new_default(dst);
    long n;

    mino_val *v = mino_eval_string(src, "{:a 1 :b 2 :c 3}", se);
    adv_require(ctx, v != NULL);

    for (n = 1; n <= 20; n++) {
        mino_set_fail_raw_at(dst, n);
        if (mino_clone(dst, src, v) == NULL) {
            mino_set_fail_raw_at(dst, 0);
            break;
        }
        mino_set_fail_raw_at(dst, 0);
    }
    adv_require(ctx, n <= 20);

    adv_require(ctx, mino_clone(dst, src, v) != NULL);

    mino_env_free(src, se);
    mino_env_free(dst, de);
    mino_state_free(src);
    mino_state_free(dst);
    return ADV_VERDICT_PASS;
}

/* Repeated OOM + recovery cycles don't corrupt state. */
static adv_verdict_t probe_repeated_oom_recovery(adv_probe_ctx_t *ctx) {
    mino_state *S = mino_state_new();
    mino_env *env = mino_env_new_default(S);
    int i;

    for (i = 0; i < 50; i++) {
        mino_set_fail_alloc_at(S, 3);
        /* This eval will likely fail under the injection. */
        mino_eval_string(S, "(into [] (range 50))", env);
        mino_set_fail_alloc_at(S, 0);

        /* The recovery eval must succeed every cycle. */
        adv_require(ctx, mino_eval_string(S, "(+ 1 1)", env) != NULL);
    }

    mino_env_free(S, env);
    mino_state_free(S);
    return ADV_VERDICT_PASS;
}

ADV_PROBE_REGISTER("fault_map_oom",          ADV_CAT_FAULT, 5000, 1,
                   probe_map_oom);
ADV_PROBE_REGISTER("fault_vector_oom",       ADV_CAT_FAULT, 5000, 1,
                   probe_vector_oom);
ADV_PROBE_REGISTER("fault_binding_oom",      ADV_CAT_FAULT, 5000, 1,
                   probe_binding_oom);
ADV_PROBE_REGISTER("fault_regex_oom",        ADV_CAT_FAULT, 5000, 1,
                   probe_regex_oom);
ADV_PROBE_REGISTER("fault_oom_catchable",    ADV_CAT_FAULT, 5000, 1,
                   probe_oom_catchable);
ADV_PROBE_REGISTER("fault_clone_vector_oom", ADV_CAT_FAULT, 5000, 1,
                   probe_clone_vector_oom);
ADV_PROBE_REGISTER("fault_clone_map_oom",    ADV_CAT_FAULT, 5000, 1,
                   probe_clone_map_oom);
ADV_PROBE_REGISTER("fault_repeated_oom",     ADV_CAT_FAULT, 5000, 1,
                   probe_repeated_oom_recovery);
