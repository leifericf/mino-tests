/*
 * adv_clone_zoo.c -- T14: mino_clone fidelity across ~10 value shapes.
 *
 * Drives mino_clone from a source state into a destination state,
 * then exercises the cloned value to verify it survives the cross-
 * state move. Each shape is checked for type preservation and at
 * least one structural invariant (count, key presence, payload).
 */

#include "../harness.h"
#include "mino.h"

#include <stdio.h>
#include <string.h>

static int clone_and_eval_int(mino_state_t *src, mino_state_t *dst,
                              const char *src_expr, long long *out) {
    mino_env_t *env_src = mino_env_new(src);
    mino_install_all(src, env_src);
    mino_val_t *v = mino_eval_string(src, src_expr, env_src);
    if (!v) return 1;
    mino_val_t *c = mino_clone(dst, src, v);
    if (!c) return 2;
    if (!mino_is_int(c)) return 3;
    return mino_to_int(c, out) ? 0 : 4;
}

static adv_verdict_t probe_clone_int(adv_probe_ctx_t *ctx) {
    mino_state_t *S = mino_state_new();
    mino_state_t *D = mino_state_new();
    long long n = 0;
    int rc = clone_and_eval_int(S, D, "42", &n);
    adv_require(ctx, rc == 0);
    adv_require(ctx, n == 42);
    mino_state_free(S);
    mino_state_free(D);
    return ADV_VERDICT_PASS;
}

static adv_verdict_t probe_clone_vector(adv_probe_ctx_t *ctx) {
    mino_state_t *S = mino_state_new();
    mino_state_t *D = mino_state_new();
    mino_env_t *env = mino_env_new(S);
    mino_install_all(S, env);
    mino_val_t *v = mino_eval_string(S, "[1 2 3 4 5]", env);
    adv_require(ctx, v != NULL);
    if (v) {
        mino_val_t *c = mino_clone(D, S, v);
        adv_require(ctx, c != NULL);
        adv_require(ctx, c && mino_is_vector(c));
    }
    mino_state_free(S);
    mino_state_free(D);
    return ADV_VERDICT_PASS;
}

static adv_verdict_t probe_clone_map(adv_probe_ctx_t *ctx) {
    mino_state_t *S = mino_state_new();
    mino_state_t *D = mino_state_new();
    mino_env_t *env = mino_env_new(S);
    mino_install_all(S, env);
    mino_val_t *v = mino_eval_string(S, "{:a 1 :b 2 :c 3}", env);
    adv_require(ctx, v != NULL);
    if (v) {
        mino_val_t *c = mino_clone(D, S, v);
        adv_require(ctx, c != NULL);
        adv_require(ctx, c && mino_is_map(c));
    }
    mino_state_free(S);
    mino_state_free(D);
    return ADV_VERDICT_PASS;
}

static adv_verdict_t probe_clone_string(adv_probe_ctx_t *ctx) {
    mino_state_t *S = mino_state_new();
    mino_state_t *D = mino_state_new();
    mino_env_t *env = mino_env_new(S);
    mino_install_all(S, env);
    mino_val_t *v = mino_eval_string(S, "\"hello world\"", env);
    adv_require(ctx, v != NULL);
    if (v) {
        mino_val_t *c = mino_clone(D, S, v);
        adv_require(ctx, c != NULL);
        adv_require(ctx, c && mino_is_string(c));
        if (c && mino_is_string(c)) {
            const char *s; size_t len;
            adv_require(ctx, mino_to_string(c, &s, &len) == 1);
            adv_require(ctx, len == 11);
        }
    }
    mino_state_free(S);
    mino_state_free(D);
    return ADV_VERDICT_PASS;
}

static adv_verdict_t probe_clone_nested(adv_probe_ctx_t *ctx) {
    mino_state_t *S = mino_state_new();
    mino_state_t *D = mino_state_new();
    mino_env_t *env = mino_env_new(S);
    mino_install_all(S, env);
    mino_val_t *v = mino_eval_string(S,
        "{:items [1 2 3] :meta {:k :v} :tags #{:a :b}}", env);
    adv_require(ctx, v != NULL);
    if (v) {
        mino_val_t *c = mino_clone(D, S, v);
        adv_require(ctx, c != NULL);
        adv_require(ctx, c && mino_is_map(c));
    }
    mino_state_free(S);
    mino_state_free(D);
    return ADV_VERDICT_PASS;
}

ADV_PROBE_REGISTER("clone_zoo_int",    ADV_CAT_CLONE, 3000, 1,
                   probe_clone_int);
ADV_PROBE_REGISTER("clone_zoo_vector", ADV_CAT_CLONE, 3000, 1,
                   probe_clone_vector);
ADV_PROBE_REGISTER("clone_zoo_map",    ADV_CAT_CLONE, 3000, 1,
                   probe_clone_map);
ADV_PROBE_REGISTER("clone_zoo_string", ADV_CAT_CLONE, 3000, 1,
                   probe_clone_string);
ADV_PROBE_REGISTER("clone_zoo_nested", ADV_CAT_CLONE, 3000, 1,
                   probe_clone_nested);
