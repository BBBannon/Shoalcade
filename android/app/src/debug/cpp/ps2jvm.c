// Shoalcade Dev only (PS2 test): Play!'s emulation thread attaches to Java through a JavaVM pointer that is normally
// set in JNI_OnLoad. LibretroDroid loads cores with dlopen, so that never runs and the thread crashes (null JavaVM).
// prime() loads the core first and hands it the JavaVM; LibretroDroid's own dlopen of the same path gets this copy.
#include <dlfcn.h>
#include <jni.h>

typedef void (*set_vm_fn)(JavaVM *);

JNIEXPORT jstring JNICALL
Java_uk_benmarks_shoalcade_devps2_Ps2Jvm_prime(JNIEnv *env, jclass cls, jstring path) {
    (void) cls;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    void *h = dlopen(p, RTLD_NOW); // kept open on purpose, so the pointer stays set for this process
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (!h) return (*env)->NewStringUTF(env, dlerror());
    set_vm_fn set = (set_vm_fn) dlsym(h, "_ZN9Framework7CJavaVM9SetJavaVMEP7_JavaVM");
    if (!set) return (*env)->NewStringUTF(env, "Play! core has no SetJavaVM");
    JavaVM *vm = NULL;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK || !vm) return (*env)->NewStringUTF(env, "no JavaVM");
    set(vm);
    return NULL;
}
