/*
 * harness.c -- C99 implementation of the mino-tests probe harness.
 *
 * No platform branches beyond pthread / _WIN32. xorshift64* RNG for
 * deterministic replay. clock_gettime(CLOCK_MONOTONIC) for timing.
 */

#include "harness.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#endif

/* --- timing --- */

double adv_now_ms(void) {
#ifdef _WIN32
    static LARGE_INTEGER freq = {0};
    if (freq.QuadPart == 0) QueryPerformanceFrequency(&freq);
    LARGE_INTEGER c;
    QueryPerformanceCounter(&c);
    return (double)c.QuadPart * 1000.0 / (double)freq.QuadPart;
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1e6;
#endif
}

/* --- xorshift64* --- */

void adv_rng_seed(adv_rng_t *rng, uint64_t seed) {
    /* Avoid the all-zero fixed point. */
    rng->s = seed ? seed : 0x9E3779B97F4A7C15ULL;
}

uint64_t adv_rng_next_u64(adv_rng_t *rng) {
    uint64_t x = rng->s;
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    rng->s = x;
    return x * 0x2545F4914F6CDD1DULL;
}

uint32_t adv_rng_next_u32(adv_rng_t *rng) {
    return (uint32_t)(adv_rng_next_u64(rng) >> 32);
}

int adv_rng_range_i32(adv_rng_t *rng, int lo, int hi) {
    if (hi < lo) return lo;
    uint32_t span = (uint32_t)(hi - lo + 1);
    return lo + (int)(adv_rng_next_u32(rng) % span);
}

double adv_rng_unit(adv_rng_t *rng) {
    /* 53 high bits -> [0.0, 1.0). */
    uint64_t b = adv_rng_next_u64(rng) >> 11;
    return (double)b / (double)(1ULL << 53);
}

/* --- registry --- */

static const adv_probe_t *g_probes[ADV_MAX_PROBES];
static int g_probe_count = 0;

void adv_registry_add(const adv_probe_t *p) {
    if (g_probe_count >= ADV_MAX_PROBES) {
        fprintf(stderr, "adv_registry_add: probe overflow (max %d)\n",
                ADV_MAX_PROBES);
        return;
    }
    g_probes[g_probe_count++] = p;
}

int adv_registry_count(void) {
    return g_probe_count;
}

const adv_probe_t *adv_registry_get(int i) {
    if (i < 0 || i >= g_probe_count) return NULL;
    return g_probes[i];
}

/* --- probe lifecycle --- */

void adv_probe_begin(adv_probe_ctx_t *ctx, const adv_probe_t *p,
                     uint64_t seed) {
    ctx->probe = p;
    ctx->seed  = seed;
    adv_rng_seed(&ctx->rng, seed);
    ctx->require_failures = 0;
    ctx->require_total    = 0;
    ctx->start_ms         = adv_now_ms();
    if (ctx->out == NULL) ctx->out = stdout;
}

adv_verdict_t adv_probe_end(adv_probe_ctx_t *ctx, adv_verdict_t v) {
    double elapsed = adv_now_ms() - ctx->start_ms;
    const char *verdict = (v == ADV_VERDICT_PASS) ? "pass" :
                          (v == ADV_VERDICT_FAIL) ? "fail" : "skip";
    if (ctx->require_failures > 0) verdict = "fail";
    fprintf(ctx->out,
        "{\"probe\":\"%s\",\"verdict\":\"%s\",\"seed\":%llu,"
        "\"elapsed_ms\":%.2f,\"requires\":%d,\"failures\":%d}\n",
        ctx->probe->name, verdict,
        (unsigned long long)ctx->seed,
        elapsed,
        ctx->require_total,
        ctx->require_failures);
    fflush(ctx->out);
    return (ctx->require_failures > 0) ? ADV_VERDICT_FAIL : v;
}

int adv_require_impl(adv_probe_ctx_t *ctx, int ok,
                     const char *file, int line, const char *expr) {
    ctx->require_total++;
    if (!ok) {
        ctx->require_failures++;
        fprintf(stderr, "[%s] adv_require FAIL at %s:%d  %s  (seed=%llu)\n",
                ctx->probe->name, file, line, expr,
                (unsigned long long)ctx->seed);
    }
    return ok;
}

uint64_t adv_seed_get(const adv_probe_ctx_t *ctx) { return ctx->seed; }

void adv_seed_set(adv_probe_ctx_t *ctx, uint64_t seed) {
    ctx->seed = seed;
    adv_rng_seed(&ctx->rng, seed);
}

void adv_json_emit(adv_probe_ctx_t *ctx, const char *key, const char *value) {
    fprintf(ctx->out, "{\"probe\":\"%s\",\"%s\":\"%s\"}\n",
            ctx->probe->name, key, value);
    fflush(ctx->out);
}

void adv_json_emit_i(adv_probe_ctx_t *ctx, const char *key, int64_t value) {
    fprintf(ctx->out, "{\"probe\":\"%s\",\"%s\":%lld}\n",
            ctx->probe->name, key, (long long)value);
    fflush(ctx->out);
}

/* --- driver main --- */

static int parse_seed(int argc, char **argv, uint64_t *out) {
    for (int i = 1; i < argc - 1; i++) {
        if (strcmp(argv[i], "--seed") == 0) {
            *out = strtoull(argv[i + 1], NULL, 10);
            return 1;
        }
    }
    return 0;
}

static const char *parse_filter(int argc, char **argv) {
    for (int i = 1; i < argc - 1; i++) {
        if (strcmp(argv[i], "--filter") == 0) return argv[i + 1];
    }
    return NULL;
}

int adv_driver_main(int argc, char **argv) {
    uint64_t seed = 0;
    parse_seed(argc, argv, &seed);
    const char *filter = parse_filter(argc, argv);

    int n = adv_registry_count();
    if (n == 0) {
        fprintf(stdout, "{\"total\":0,\"failed\":0}\n");
        return 0;
    }

    int total = 0, failed = 0;
    for (int i = 0; i < n; i++) {
        const adv_probe_t *p = adv_registry_get(i);
        if (filter && !strstr(p->name, filter)) continue;
        adv_probe_ctx_t ctx;
        memset(&ctx, 0, sizeof(ctx));
        adv_probe_begin(&ctx, p, seed ^ ((uint64_t)i * 0x9E3779B97F4A7C15ULL));
        adv_verdict_t v = p->fn(&ctx);
        v = adv_probe_end(&ctx, v);
        total++;
        if (v == ADV_VERDICT_FAIL) failed++;
    }
    fprintf(stdout, "{\"total\":%d,\"failed\":%d}\n", total, failed);
    return failed > 0 ? 1 : 0;
}
