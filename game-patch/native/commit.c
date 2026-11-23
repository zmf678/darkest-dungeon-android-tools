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
 * This import runs the file finalization walk at the end of CommitSaveData.
 */
static const uintptr_t COMMIT_CALL = 0xab338;
static const uintptr_t NFTW_IMPORT = 0x127590;
static const uint32_t EXPECTED_CALL = 0x9401782e;

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
