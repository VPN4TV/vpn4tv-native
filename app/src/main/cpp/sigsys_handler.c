/*
 * SIGSYS handler for Android seccomp filter compatibility.
 *
 * Go runtimes 1.23+ sometimes issue syscalls that Android < 12 blocks via
 * seccomp (pidfd_open=434, clock_gettime64=422 on arm32, etc.). The kernel
 * then sends SIGSYS and the process dies. Go's built-in handler is supposed
 * to catch this and fall back, but it's unreliable when the Go runtime is
 * loaded as a gomobile shared library (issue golang/go#70508).
 *
 * We install our own sigaction(SIGSYS) in JNI_OnLoad. The handler writes
 * -ENOSYS into the ABI-specific return-value register and lets execution
 * resume, so Go's syscall wrapper believes the syscall is unsupported and
 * picks an older fallback.
 *
 * IMPORTANT: this library must be loaded BEFORE libbox.so (which transitively
 * loads libgojni.so / Go runtime). See [Application] class init.
 */

#include <jni.h>
#include <signal.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

#define TAG "sigsys_handler"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static struct sigaction g_prev_action;

static void sigsys_handler(int sig, siginfo_t *info, void *ucontext_void) {
    if (info == NULL || info->si_code != 1 /* SYS_SECCOMP */) {
        if (g_prev_action.sa_flags & SA_SIGINFO) {
            if (g_prev_action.sa_sigaction != NULL) {
                g_prev_action.sa_sigaction(sig, info, ucontext_void);
                return;
            }
        } else if (g_prev_action.sa_handler != NULL &&
                   g_prev_action.sa_handler != SIG_DFL &&
                   g_prev_action.sa_handler != SIG_IGN) {
            g_prev_action.sa_handler(sig);
            return;
        }
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }

    ucontext_t *uc = (ucontext_t *)ucontext_void;

#if defined(__aarch64__)
    uc->uc_mcontext.regs[0] = (unsigned long)(-ENOSYS);
#elif defined(__arm__)
    uc->uc_mcontext.arm_r0 = (unsigned long)(-ENOSYS);
#elif defined(__x86_64__)
    uc->uc_mcontext.gregs[REG_RAX] = (long long)(-ENOSYS);
#elif defined(__i386__)
    uc->uc_mcontext.gregs[REG_EAX] = (long)(-ENOSYS);
#else
#error "unsupported architecture"
#endif
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = sigsys_handler;
    action.sa_flags = SA_SIGINFO | SA_NODEFER | SA_RESTART;
    sigemptyset(&action.sa_mask);

    if (sigaction(SIGSYS, &action, &g_prev_action) != 0) {
        LOGE("failed to install SIGSYS handler: %s", strerror(errno));
        return JNI_VERSION_1_6;
    }
    LOGI("SIGSYS seccomp workaround handler installed");
    return JNI_VERSION_1_6;
}
