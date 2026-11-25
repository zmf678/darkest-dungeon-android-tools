#define _GNU_SOURCE
#define _XOPEN_SOURCE 700

#include <dlfcn.h>
#include <ftw.h>
#include <jni.h>
#include <link.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

/* Offsets for the hash-checked Wicked 2.2.2 libddnx.so.
 * All three hook targets live on the same GOT data page (0x127000), so hooking
 * only toggles PROT_READ <-> PROT_READ|PROT_WRITE and never triggers SELinux execmod.
 */
static const uintptr_t COMMIT_CALL = 0xab338;
static const uintptr_t NFTW_IMPORT = 0x127590;
static const uintptr_t DLSYM_IMPORT = 0x127738;
static const uintptr_t EGL_GET_PROC_IMPORT = 0x127798;
static const uintptr_t TARGET_FPS_DATA = 0x12bdc8;
static const uint32_t EXPECTED_CALL = 0x9401782e;

typedef int (*WalkCallback)(const char *, const struct stat *, int, struct FTW *);
typedef int (*Walk)(const char *, WalkCallback, int, int);
typedef void *(*DlsymFn)(void *, const char *);
typedef void *(*EglGetProcFn)(const char *);
typedef int32_t (*SetFrameRateFn)(void *, float, int8_t);
typedef unsigned int (*EglPresentationTimeFn)(void *, void *, int64_t);

static JavaVM *vm;
static jclass bridge;
static jmethodID after_commit;
static Walk original_walk;
static uintptr_t commit_return;
static uintptr_t ddnx_base;

static DlsymFn original_dlsym;
static EglGetProcFn original_egl_get_proc;
static SetFrameRateFn real_set_frame_rate;
static EglPresentationTimeFn real_presentation_time;
static int target_fps = 60;
static int64_t last_pts_ns = 0;

static void capture(void) {
    JNIEnv *env;
    jint status = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
    int attach = status == JNI_EDETACHED;
    if (status != JNI_OK && !attach) return;
    if (attach && (*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return;
    (*env)->CallStaticVoidMethod(env, bridge, after_commit);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    if (attach) (*vm)->DetachCurrentThread(vm);
}

static int walk_then_capture(const char *root, WalkCallback callback, int fd_limit, int flags) {
    int result = original_walk(root, callback, fd_limit, flags);
    if (result == 0 && (uintptr_t)__builtin_return_address(0) == commit_return) capture();
    return result;
}

static int32_t hooked_set_frame_rate(void *window, float frame_rate, int8_t compatibility) {
    SetFrameRateFn fn = __atomic_load_n(&real_set_frame_rate, __ATOMIC_ACQUIRE);
    if (!fn) return 0;
    int fps = __atomic_load_n(&target_fps, __ATOMIC_ACQUIRE);
    if (fps > 60 && frame_rate > 35.0f) {
        frame_rate = (float)fps;
    }
    return fn(window, frame_rate, compatibility);
}

static void *hooked_dlsym(void *handle, const char *symbol) {
    void *res = original_dlsym(handle, symbol);
    if (res && symbol && strcmp(symbol, "ANativeWindow_setFrameRate") == 0) {
        __atomic_store_n(&real_set_frame_rate, (SetFrameRateFn)res, __ATOMIC_RELEASE);
        return (void *)hooked_set_frame_rate;
    }
    return res;
}

static unsigned int hooked_presentation_time(void *dpy, void *surface, int64_t pts) {
    EglPresentationTimeFn fn = __atomic_load_n(&real_presentation_time, __ATOMIC_ACQUIRE);
    if (!fn) return 1;
    int fps = __atomic_load_n(&target_fps, __ATOMIC_ACQUIRE);
    if (fps <= 60) {
        return fn(dpy, surface, pts);
    }
    /* Preserve 30 fps cutscene pacing when libddnx.so explicitly selects 30.0f. */
    if (ddnx_base) {
        float engine_fps = *(const float *)(ddnx_base + TARGET_FPS_DATA);
        if (engine_fps > 1.0f && engine_fps <= 35.0f) {
            last_pts_ns = 0;
            return fn(dpy, surface, pts);
        }
    }
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t now_ns = (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
    int64_t period_ns = 1000000000LL / (int64_t)fps;
    int64_t next_ns = last_pts_ns + period_ns;
    if (next_ns < now_ns) next_ns = now_ns;
    last_pts_ns = next_ns;
    return fn(dpy, surface, next_ns);
}

static void *hooked_egl_get_proc(const char *procname) {
    void *res = original_egl_get_proc(procname);
    if (res && procname && strcmp(procname, "eglPresentationTimeANDROID") == 0) {
        __atomic_store_n(&real_presentation_time, (EglPresentationTimeFn)res, __ATOMIC_RELEASE);
        return (void *)hooked_presentation_time;
    }
    return res;
}

static int find_library(struct dl_phdr_info *image, size_t size, void *result) {
    (void)size;
    const char *name = strrchr(image->dlpi_name, '/');
    name = name ? name + 1 : image->dlpi_name;
    if (strcmp(name, "libddnx.so") != 0) return 0;
    *(uintptr_t *)result = image->dlpi_addr;
    return 1;
}

static int patch_got_slot(uintptr_t slot_addr, void *expected, void *replacement, void **saved_out) {
    void **entry = (void **)slot_addr;
    void *current = __atomic_load_n(entry, __ATOMIC_ACQUIRE);
    if (current == replacement) return 0;
    if (!current || (expected && current != expected)) return 2;

    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return 6;
    void *page = (void *)((uintptr_t)entry & ~((uintptr_t)page_size - 1));
    if (mprotect(page, (size_t)page_size, PROT_READ | PROT_WRITE) != 0) return 7;
    if (saved_out) *saved_out = current;
    __atomic_store_n(entry, replacement, __ATOMIC_RELEASE);
    if (mprotect(page, (size_t)page_size, PROT_READ) != 0) return 8;
    return 0;
}

JNIEXPORT jint JNICALL
Java_dev_torcharchive_game_GameArchive_installCommitCallback(JNIEnv *env, jclass type) {
    if (original_walk) return 0;
    uintptr_t base = 0;
    dl_iterate_phdr(find_library, &base);
    if (!base || *(const uint32_t *)(base + COMMIT_CALL) != EXPECTED_CALL) return 1;
    ddnx_base = base;

    if ((*env)->GetJavaVM(env, &vm) != JNI_OK) return 3;
    after_commit = (*env)->GetStaticMethodID(env, type, "afterCommit", "()V");
    if (!after_commit) return 4;
    bridge = (*env)->NewGlobalRef(env, type);
    if (!bridge) return 5;

    commit_return = base + COMMIT_CALL + sizeof(uint32_t);
    void *saved = NULL;
    int rc = patch_got_slot(base + NFTW_IMPORT, dlsym(RTLD_DEFAULT, "nftw"),
                            (void *)walk_then_capture, &saved);
    if (rc != 0) return rc;
    original_walk = (Walk)saved;
    return 0;
}

JNIEXPORT jint JNICALL
Java_dev_torcharchive_game_GameArchive_setNativeTargetFps(JNIEnv *env, jclass type, jint fps) {
    (void)env;
    (void)type;
    if (fps < 30) fps = 30;
    if (fps > 165) fps = 165;
    __atomic_store_n(&target_fps, (int)fps, __ATOMIC_RELEASE);
    last_pts_ns = 0;

    if (fps <= 60) return 0;

    uintptr_t base = ddnx_base;
    if (!base) {
        dl_iterate_phdr(find_library, &base);
        if (!base) return 1;
        ddnx_base = base;
    }

    if (!original_dlsym) {
        void *saved_dlsym = NULL;
        int rc1 = patch_got_slot(base + DLSYM_IMPORT, (void *)dlsym,
                                 (void *)hooked_dlsym, &saved_dlsym);
        if (rc1 != 0) return 10 + rc1;
        original_dlsym = (DlsymFn)saved_dlsym;
    }

    if (!original_egl_get_proc) {
        void *egl_proc = dlsym(RTLD_DEFAULT, "eglGetProcAddress");
        void *saved_egl = NULL;
        int rc2 = patch_got_slot(base + EGL_GET_PROC_IMPORT, egl_proc,
                                 (void *)hooked_egl_get_proc, &saved_egl);
        if (rc2 != 0) return 20 + rc2;
        original_egl_get_proc = (EglGetProcFn)saved_egl;
    }

    return 0;
}
