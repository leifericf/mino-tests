/*
 * topology.c -- cross-state topology helpers for embed probes.
 *
 * See topology.h. All operations take the node-local mutex; the
 * harness threads coordinate exclusively through these helpers so
 * each peer's GC roots stay clean.
 */

#include "topology.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define ADV_TOPO_INBOX_DEFAULT 64

static int node_init(adv_topo_node_t *node, int idx) {
    node->idx          = idx;
    node->S            = mino_state_new();
    if (!node->S) return 1;
    if (pthread_mutex_init(&node->lock, NULL) != 0) {
        mino_state_free(node->S);
        node->S = NULL;
        return 1;
    }
    node->inbox_max    = ADV_TOPO_INBOX_DEFAULT;
    node->inbox        = (mino_val_t **)calloc(node->inbox_max,
                                               sizeof(mino_val_t *));
    if (!node->inbox) {
        pthread_mutex_destroy(&node->lock);
        mino_state_free(node->S);
        node->S = NULL;
        return 1;
    }
    node->inbox_count  = 0;
    node->processed    = 0;
    return 0;
}

static void node_free(adv_topo_node_t *node) {
    if (node->S) {
        mino_state_free(node->S);
        node->S = NULL;
    }
    if (node->inbox) {
        free(node->inbox);
        node->inbox = NULL;
    }
    pthread_mutex_destroy(&node->lock);
}

int adv_topo_build(adv_topo_t *t, adv_topo_shape_t shape, int n) {
    if (n < 2 || n > ADV_TOPO_MAX_NODES) return 1;
    memset(t, 0, sizeof(*t));
    t->shape = shape;
    t->n     = n;
    for (int i = 0; i < n; i++) {
        if (node_init(&t->nodes[i], i) != 0) {
            for (int j = 0; j < i; j++) node_free(&t->nodes[j]);
            return 1;
        }
    }
    /* successor wiring */
    for (int i = 0; i < n; i++) {
        switch (shape) {
        case ADV_TOPO_RING:
            t->next[i] = (i + 1) % n;
            break;
        case ADV_TOPO_STAR:
            /* Hub is node 0; spokes forward to hub; hub forwards to
             * next spoke round-robin via a small static counter that
             * we keep in t->next[0]. Subsequent hub-step picks the
             * spoke (cur + 1) % (n - 1) + 1. */
            t->next[i] = (i == 0) ? 1 : 0;
            break;
        case ADV_TOPO_MESH:
            /* Mesh has no fixed "next" -- senders pick a destination
             * at post-time. Default the next field to (i+1) % n so a
             * naive stepper still progresses. */
            t->next[i] = (i + 1) % n;
            break;
        default:
            for (int j = 0; j < n; j++) node_free(&t->nodes[j]);
            return 1;
        }
    }
    return 0;
}

void adv_topo_destroy(adv_topo_t *t) {
    for (int i = 0; i < t->n; i++) node_free(&t->nodes[i]);
    memset(t, 0, sizeof(*t));
}

int adv_topo_post(adv_topo_t *t, int src_idx, int dst_idx,
                  mino_val_t *val) {
    if (src_idx < 0 || src_idx >= t->n) return 1;
    if (dst_idx < 0 || dst_idx >= t->n) return 1;
    if (src_idx == dst_idx)             return 1;
    adv_topo_node_t *src = &t->nodes[src_idx];
    adv_topo_node_t *dst = &t->nodes[dst_idx];

    /* Clone under the destination's lock so its GC roots don't move
     * underneath the clone walk. */
    pthread_mutex_lock(&dst->lock);
    mino_val_t *cloned = mino_clone(dst->S, src->S, val);
    if (cloned == NULL) {
        pthread_mutex_unlock(&dst->lock);
        return 2;
    }
    if (dst->inbox_count >= dst->inbox_max) {
        pthread_mutex_unlock(&dst->lock);
        return 3;
    }
    dst->inbox[dst->inbox_count++] = cloned;
    pthread_mutex_unlock(&dst->lock);
    return 0;
}

int adv_topo_step(adv_topo_t *t) {
    int forwarded = 0;
    for (int i = 0; i < t->n; i++) {
        adv_topo_node_t *node = &t->nodes[i];
        mino_val_t *msg = NULL;
        pthread_mutex_lock(&node->lock);
        if (node->inbox_count > 0) {
            msg = node->inbox[0];
            for (int k = 1; k < node->inbox_count; k++)
                node->inbox[k - 1] = node->inbox[k];
            node->inbox_count--;
            node->processed++;
        }
        pthread_mutex_unlock(&node->lock);

        if (msg) {
            int dst = t->next[i];
            if (adv_topo_post(t, i, dst, msg) == 0) forwarded++;
        }
    }
    return forwarded;
}

void adv_topo_processed(const adv_topo_t *t, int *out) {
    for (int i = 0; i < t->n; i++) out[i] = t->nodes[i].processed;
}
