/*
 * topology.h -- cross-state topology helpers.
 *
 * Stand up small networks of mino_state_t instances communicating via
 * mino_clone-shaped messages. Used by embed probes to surface races,
 * deadlocks, and pathological slowdowns under concurrent access.
 *
 * Three shapes:
 *   - ring(N)  -- each node forwards to the next; messages travel
 *                 round-robin.
 *   - star(N)  -- one hub, N-1 spokes; spokes report to the hub.
 *   - mesh(N)  -- each node knows every other node directly.
 *
 * Each shape exposes the same primitive: post a value from origin
 * state to destination state via mino_clone, optionally with an
 * attached worker-thread payload that runs in the destination's host
 * thread pool.
 */

#ifndef MINO_TESTS_ADV_TOPOLOGY_H
#define MINO_TESTS_ADV_TOPOLOGY_H

#include "harness.h"
#include "mino.h"

#include <pthread.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ADV_TOPO_MAX_NODES 32

typedef enum {
    ADV_TOPO_RING = 1,
    ADV_TOPO_STAR = 2,
    ADV_TOPO_MESH = 3
} adv_topo_shape_t;

typedef struct adv_topo_node {
    int                idx;
    mino_state_t      *S;
    pthread_mutex_t    lock;
    int                inbox_count;
    int                inbox_max;
    mino_val_t       **inbox;       /* values cloned from peers */
    int                processed;   /* monotonic count */
} adv_topo_node_t;

typedef struct adv_topo {
    adv_topo_shape_t   shape;
    int                n;
    adv_topo_node_t    nodes[ADV_TOPO_MAX_NODES];
    /* For shapes that need a "next" node lookup (ring) we keep an
     * explicit successor index per node so the rule is data-driven
     * instead of computed from shape at every step. */
    int                next[ADV_TOPO_MAX_NODES];
} adv_topo_t;

/* Build a topology. Allocates per-node states via mino_state_new and
 * wires successor indices. Returns 0 on success, non-zero on failure
 * (e.g., state_new returned NULL for any node). */
int  adv_topo_build(adv_topo_t *t, adv_topo_shape_t shape, int n);

/* Tear down a topology: free all node states + inbox arrays. */
void adv_topo_destroy(adv_topo_t *t);

/* Clone VAL from node SRC to node DST's inbox. Returns 0 on success,
 * non-zero if mino_clone returned NULL or DST's inbox is full. */
int  adv_topo_post(adv_topo_t *t, int src_idx, int dst_idx,
                   mino_val_t *val);

/* Run one "round" of message passing. For each node with a message in
 * its inbox, pop it and forward to t->next[i]. Returns the number of
 * messages forwarded this round. The caller decides when to stop. */
int  adv_topo_step(adv_topo_t *t);

/* Snapshot per-node processed counts into OUT (must be size >= t->n).
 * Useful for asserting "all nodes saw at least one message". */
void adv_topo_processed(const adv_topo_t *t, int *out);

#ifdef __cplusplus
}
#endif

#endif /* MINO_TESTS_ADV_TOPOLOGY_H */
