#define _GNU_SOURCE
#define _XOPEN_SOURCE 700

#include <dlfcn.h>
#include <ftw.h>
#include <jni.h>
#include <link.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

/* Offsets for the hash-checked Wicked 2.2.2 libddnx.so.
 * COMMIT_CALL runs the file finalization walk at the end of CommitSaveData.
 * FPS_MOV_ADDR holds `movz w8, #0x4270, lsl #16` (60.0f) inside nx_eglSwapBuffers,
 * which drives both ANativeWindow_setFrameRate and eglPresentationTimeANDROID pacing.
 */
static const uintptr_t COMMIT_CALL = 0xab338;
static const uintptr_t NFTW_IMPORT = 0x127590;
static const uint32_t EXPECTED_CALL = 0x9401782e;
static const uintptr_t FPS_MOV_ADDR = 0xb13ac;
static const uint32_t MOVZ_W8_LSL16_MASK = 0xFFE0001Fu;
static const uint32_t MOVZ_W8_LSL16_OP = 0x52A00008u;

typedef int (*WalkCallback)(const char *, const struct stat *, int, struct FTW *);
typedef int (*Walk)(const char *, WalkCallback, int, int);

static JavaVM *vm;
static jclass bridge;
static jmethodID after_commit;
static Walk original_walk;
static uintptr_t commit_return;

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

static int find_library(struct dl_phdr_info *image, size_t size, void *result) {
    (void)size;
    const char *name = strrchr(image->dlpi_name, '/');
    name = name ? name + 1 : image->dlpi_name;
    if (strcmp(name, "libddnx.so") != 0) return 0;
    *(uintptr_t *)result = image->dlpi_addr;
    return 1;
}

JNIEXPORT jint JNICALL
Java_dev_torcharchive_game_GameArchive_installCommitCallback(JNIEnv *env, jclass type) {
    if (original_walk) return 0;
    uintptr_t base = 0;
    dl_iterate_phdr(find_library, &base);
    if (!base || *(const uint32_t *)(base + COMMIT_CALL) != EXPECTED_CALL) return 1;

    Walk *entry = (Walk *)(base + NFTW_IMPORT);
    Walk current = __atomic_load_n(entry, __ATOMIC_ACQUIRE);
    if (!current || current != (Walk)dlsym(RTLD_DEFAULT, "nftw")) return 2;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK) return 3;
    after_commit = (*env)->GetStaticMethodID(env, type, "afterCommit", "()V");
    if (!after_commit) return 4;
    bridge = (*env)->NewGlobalRef(env, type);
    if (!bridge) return 5;

    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return 6;
    void *page = (void *)((uintptr_t)entry & ~((uintptr_t)page_size - 1));
    if (mprotect(page, (size_t)page_size, PROT_READ | PROT_WRITE) != 0) return 7;
    original_walk = current;
    commit_return = base + COMMIT_CALL + sizeof(uint32_t);
    __atomic_store_n(entry, walk_then_capture, __ATOMIC_RELEASE);
    if (mprotect(page, (size_t)page_size, PROT_READ) != 0) return 8;
    return 0;
}

JNIEXPORT jint JNICALL
Java_dev_torcharchive_game_GameArchive_setNativeTargetFps(JNIEnv *env, jclass type, jint targetFps) {
    (void)env;
    (void)type;
    if (targetFps < 30) targetFps = 30;
    if (targetFps > 165) targetFps = 165;

    uintptr_t base = 0;
    dl_iterate_phdr(find_library, &base);
    if (!base) return 1;

    uint32_t *insn = (uint32_t *)(base + FPS_MOV_ADDR);
    uint32_t current = *insn;
    if ((current & MOVZ_W8_LSL16_MASK) != MOVZ_W8_LSL16_OP) return 2;

    float fps = (float)targetFps;
    uint32_t bits = 0;
    memcpy(&bits, &fps, sizeof(bits));
    uint16_t hi16 = (uint16_t)(bits >> 16);
    uint32_t desired = MOVZ_W8_LSL16_OP | ((uint32_t)hi16 << 5);
    if (current == desired) return 0;

    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return 3;
    void *page = (void *)((uintptr_t)insn & ~((uintptr_t)page_size - 1));
    if (mprotect(page, (size_t)page_size, PROT_READ | PROT_WRITE) != 0) return 4;
    *insn = desired;
    __builtin___clear_cache((char *)insn, (char *)(insn + 1));
    if (mprotect(page, (size_t)page_size, PROT_READ | PROT_EXEC) != 0) return 5;
    return 0;
}
