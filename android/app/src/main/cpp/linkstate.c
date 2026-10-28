/* Shoalcade link status (GPL-3.0). Kotlin loads this library; the shim links against it,
 * so both see the same values. */
#include <jni.h>
#include <stdatomic.h>
#include "linkstate.h"

static atomic_int g_state = LINK_OFF;
static atomic_long g_rx = 0, g_tx = 0;

void sc_link_set_state(int s) { atomic_store(&g_state, s); }
int sc_link_state(void) { return atomic_load(&g_state); }
void sc_link_count(int rx, int tx) { atomic_fetch_add(&g_rx, rx); atomic_fetch_add(&g_tx, tx); }
void sc_link_reset(void) { atomic_store(&g_state, LINK_OFF); atomic_store(&g_rx, 0); atomic_store(&g_tx, 0); }

JNIEXPORT jint JNICALL Java_uk_benmarks_shoalcade_LinkNative_state(JNIEnv *e, jclass c) {
    (void)e; (void)c; return sc_link_state();
}
JNIEXPORT jlong JNICALL Java_uk_benmarks_shoalcade_LinkNative_received(JNIEnv *e, jclass c) {
    (void)e; (void)c; return atomic_load(&g_rx);
}
JNIEXPORT jlong JNICALL Java_uk_benmarks_shoalcade_LinkNative_sent(JNIEnv *e, jclass c) {
    (void)e; (void)c; return atomic_load(&g_tx);
}
JNIEXPORT void JNICALL Java_uk_benmarks_shoalcade_LinkNative_reset(JNIEnv *e, jclass c) {
    (void)e; (void)c; sc_link_reset();
}
