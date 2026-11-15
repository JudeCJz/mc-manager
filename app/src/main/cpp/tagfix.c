// LD_PRELOAD shim: turns off bionic heap pointer tagging (TBI) before the JVM starts.
// HotSpot stores data in the top byte of pointers, which trips bionic's
// "Pointer tag was truncated" abort on Android 11+.
#include <malloc.h>

#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL (-204)
#endif
#ifndef M_HEAP_TAGGING_LEVEL_NONE
#define M_HEAP_TAGGING_LEVEL_NONE 0
#endif

__attribute__((constructor(101)))
static void mcmanager_disable_heap_tagging(void) {
    mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE);
}
