/*
 * driver.c -- main() for the C-side harness binary.
 *
 * Linked against harness.c + each probe TU under embed/. The driver
 * iterates the registry, runs each probe, and emits one JSON line
 * per probe plus a final summary.
 */

#include "harness.h"

int main(int argc, char **argv) {
    return adv_driver_main(argc, argv);
}
