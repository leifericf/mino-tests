/*
 * harness.h -- public API for mino-tests's C99 probe harness.
 *
 * Each C probe is a function tagged with metadata (name, category,
 * budget_ms, sanitizer-clean flag). Probes are registered at program
 * start via ADV_PROBE_REGISTER, then dispatched by the driver in
 * tests/adv/build/.
 *
 * No platform branches beyond pthread / _WIN32. Uses
 * clock_gettime(CLOCK_MONOTONIC) for timing and a seeded xorshift64*
 * RNG for determinism.
 */

#ifndef MINO_TESTS_ADV_HARNESS_H
#define MINO_TESTS_ADV_HARNESS_H

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

/* --- timing --- */
double adv_now_ms(void);

/* --- xorshift64* RNG --- */
typedef struct {
    uint64_t s;
} adv_rng_t;

void     adv_rng_seed(adv_rng_t *rng, uint64_t seed);
uint64_t adv_rng_next_u64(adv_rng_t *rng);
uint32_t adv_rng_next_u32(adv_rng_t *rng);
int      adv_rng_range_i32(adv_rng_t *rng, int lo, int hi); /* inclusive */
double   adv_rng_unit(adv_rng_t *rng);                       /* [0.0, 1.0) */

/* --- probe metadata --- */
typedef enum {
    ADV_CAT_DIAG       = 0,
    ADV_CAT_MODE_ERR   = 1,
    ADV_CAT_REPL       = 2,
    ADV_CAT_CLI        = 3,
    ADV_CAT_LEAN       = 4,
    ADV_CAT_DEPTH      = 5,
    ADV_CAT_BUFFER     = 6,
    ADV_CAT_CLOSURE    = 7,
    ADV_CAT_CONCURRENCY= 8,
    ADV_CAT_MEM_JIT    = 9,
    ADV_CAT_GC         = 10,
    ADV_CAT_CLONE      = 11,
    ADV_CAT_POOL       = 12,
    ADV_CAT_STM        = 13,
    ADV_CAT_FAULT      = 14,
    ADV_CAT_OTHER      = 99
} adv_category_t;

typedef enum {
    ADV_VERDICT_PASS = 0,
    ADV_VERDICT_FAIL = 1,
    ADV_VERDICT_SKIP = 2
} adv_verdict_t;

typedef struct adv_probe_ctx adv_probe_ctx_t;

typedef adv_verdict_t (*adv_probe_fn)(adv_probe_ctx_t *ctx);

typedef struct {
    const char     *name;
    adv_category_t  category;
    int             budget_ms;
    int             sanitizer_clean;
    adv_probe_fn    fn;
} adv_probe_t;

/* --- probe lifecycle --- */
void          adv_probe_begin(adv_probe_ctx_t *ctx, const adv_probe_t *p,
                              uint64_t seed);
adv_verdict_t adv_probe_end(adv_probe_ctx_t *ctx, adv_verdict_t v);

/* --- requirement assertion (collects failures, doesn't abort) --- */
int adv_require_impl(adv_probe_ctx_t *ctx, int ok,
                     const char *file, int line, const char *expr);
#define adv_require(ctx, expr) \
    adv_require_impl((ctx), (expr) ? 1 : 0, __FILE__, __LINE__, #expr)

/* --- seed access from within a probe --- */
uint64_t adv_seed_get(const adv_probe_ctx_t *ctx);
void     adv_seed_set(adv_probe_ctx_t *ctx, uint64_t seed);

/* --- JSON one-line emit to stdout (or to ctx->out if set) --- */
void adv_json_emit(adv_probe_ctx_t *ctx, const char *key, const char *value);
void adv_json_emit_i(adv_probe_ctx_t *ctx, const char *key, int64_t value);

/* --- minimal probe registry (statically linked) --- */
#define ADV_MAX_PROBES 256

void  adv_registry_add(const adv_probe_t *p);
int   adv_registry_count(void);
const adv_probe_t *adv_registry_get(int i);

/* Helper macro: register a probe at link time. Place this once per
 * probe TU, after the probe function definition.
 *
 *   ADV_PROBE_REGISTER("clone_zoo", ADV_CAT_CLONE, 5000, 1,
 *                      probe_clone_zoo);
 */
#define ADV_PROBE_REGISTER(NAME, CAT, BUDGET, SAN_CLEAN, FN)              \
    static void __attribute__((constructor))                              \
    adv_register_##FN(void) {                                             \
        static const adv_probe_t _p = {                                   \
            .name = NAME, .category = CAT, .budget_ms = BUDGET,           \
            .sanitizer_clean = SAN_CLEAN, .fn = FN                        \
        };                                                                \
        adv_registry_add(&_p);                                            \
    }

/* --- internal: probe context layout (opaque to probes; defined for
 * the driver in harness.c, exposed here so sizeof() can be taken). */
struct adv_probe_ctx {
    const adv_probe_t *probe;
    uint64_t           seed;
    adv_rng_t          rng;
    int                require_failures;
    int                require_total;
    double             start_ms;
    FILE              *out;     /* default stdout */
};

/* --- driver entry point (main() in driver.c calls this) --- */
int adv_driver_main(int argc, char **argv);

#ifdef __cplusplus
}
#endif

#endif /* MINO_TESTS_ADV_HARNESS_H */
