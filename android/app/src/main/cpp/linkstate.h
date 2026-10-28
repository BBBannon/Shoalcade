/* Shared link status between the shim (writer) and Kotlin (reader, via JNI). */
#pragma once
enum { LINK_OFF = 0, LINK_WAITING_CORE = 1, LINK_ACTIVE = 2, LINK_LOST = 3, LINK_REFUSED = 4, LINK_NO_SUPPORT = 5 };
__attribute__((visibility("default"))) void sc_link_set_state(int s);
__attribute__((visibility("default"))) int sc_link_state(void);
__attribute__((visibility("default"))) void sc_link_count(int rx, int tx);
__attribute__((visibility("default"))) void sc_link_reset(void);
