// pty_bridge.c — JNI wrapper around bionic forkpty()/tcsetattr()/ioctl()
//
// Enables a real TTY for a spawned proot/sh process so the shell emits its PS1
// prompt, echoes characters back as typed, and responds to arrow keys,
// Ctrl+C as SIGINT, etc. — mirroring iOS ISHKernel behaviour.
//
// Bionic libc exposes forkpty() since API 21 (Android 5.0). This file wraps
// that plus read/write/ioctl on the master fd and process waitpid.

#include <sys/syscall.h>
#include <sys/resource.h>
#include <jni.h>
#include <pty.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
#include <signal.h>
#include <time.h>
#include <pthread.h>
#include <android/log.h>

#define LOG_TAG "PtyBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Fork the process into a new PTY and exec the child command.
// Returns the PTY master fd on success. Child pid stored into outPid[0].
// On failure returns -errno.
/*
 * [T-pty-close-fds] Close fds >= 3 in a just-forked child, async-signal-safe.
 * close_range (Linux 5.9+) when available, else a bounded close loop.
 */
static void closeInheritedFds(void) {
#ifdef __NR_close_range
    if (syscall(__NR_close_range, 3U, ~0U, 0U) == 0) return;
#endif
    long maxFd = 65536;
    struct rlimit rl;
    if (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY && rl.rlim_cur < (rlim_t) maxFd) {
        maxFd = (long) rl.rlim_cur;
    }
    for (long fd = 3; fd < maxFd; fd++) {
        syscall(__NR_close, (int) fd);
    }
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_forkExec(
    JNIEnv *env, jclass clazz,
    jstring jCmd, jobjectArray jArgv, jobjectArray jEnvp,
    jstring jCwd, jint cols, jint rows, jintArray outPid)
{
    const char *cmd = (*env)->GetStringUTFChars(env, jCmd, NULL);
    if (!cmd) return -EINVAL;

    // Build argv[] — owned strings copied from Java.
    jsize argc = (*env)->GetArrayLength(env, jArgv);
    char **argv = (char **) calloc(argc + 1, sizeof(char *));
    if (!argv) {
        (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
        return -ENOMEM;
    }
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jArgv, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(cs ? cs : "");
        (*env)->ReleaseStringUTFChars(env, s, cs);
        (*env)->DeleteLocalRef(env, s);
    }
    argv[argc] = NULL;

    // Build envp[] — same pattern.
    jsize envc = jEnvp ? (*env)->GetArrayLength(env, jEnvp) : 0;
    char **envp = (char **) calloc(envc + 1, sizeof(char *));
    if (!envp) {
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
        return -ENOMEM;
    }
    for (jsize i = 0; i < envc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jEnvp, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        envp[i] = strdup(cs ? cs : "");
        (*env)->ReleaseStringUTFChars(env, s, cs);
        (*env)->DeleteLocalRef(env, s);
    }
    envp[envc] = NULL;

    const char *cwd = jCwd ? (*env)->GetStringUTFChars(env, jCwd, NULL) : NULL;

    // Configure initial window size.
    struct winsize ws;
    ws.ws_col = (cols > 0) ? (unsigned short) cols : 80;
    ws.ws_row = (rows > 0) ? (unsigned short) rows : 24;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    // Default termios: echo ON, ICANON OFF (raw-ish so shell line editor works).
    // We actually want canonical-ish mode — the shell's readline/line editor
    // manages its own char-at-a-time state, so leave the classic defaults and
    // let the shell reconfigure termios via tcsetattr() itself.
    struct termios tio;
    memset(&tio, 0, sizeof(tio));
    tio.c_iflag = ICRNL | IXON | IUTF8;
    tio.c_oflag = OPOST | ONLCR;
    tio.c_cflag = CREAD | CS8 | HUPCL;
    tio.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN;
    cfsetispeed(&tio, B38400);
    cfsetospeed(&tio, B38400);
    tio.c_cc[VINTR]    = 0x03; // Ctrl-C
    tio.c_cc[VQUIT]    = 0x1c; // Ctrl-\.
    tio.c_cc[VERASE]   = 0x7f; // Backspace (DEL)
    tio.c_cc[VKILL]    = 0x15; // Ctrl-U
    tio.c_cc[VEOF]     = 0x04; // Ctrl-D
    tio.c_cc[VSTART]   = 0x11;
    tio.c_cc[VSTOP]    = 0x13;
    tio.c_cc[VSUSP]    = 0x1a; // Ctrl-Z
    tio.c_cc[VMIN]     = 1;
    tio.c_cc[VTIME]    = 0;

    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, &tio, &ws);
    if (pid < 0) {
        int err = errno;
        LOGE("forkpty failed: %s", strerror(err));
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        for (jsize i = 0; i < envc; i++) free(envp[i]);
        free(envp);
        (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
        if (jCwd && cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        return -err;
    }

    if (pid == 0) {
        // Child.
        if (cwd && *cwd) chdir(cwd);
        // Clear all signal handlers and unblock the usual set so the shell's
        // own signal handling works as users expect.
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);
        for (int s = 1; s < NSIG; s++) signal(s, SIG_DFL);

        // [T-pty-close-fds] Close every inherited fd above stdio before exec.
        // ProcessBuilder's child does this; forkpty does not, so the terminal's
        // proot and every guest process under it inherited whatever the app
        // had open - the native-offload listening socket, the debug server
        // port, database files - and a guest that outlived the app could keep
        // them. Only raw syscalls here: this is a fork of a multithreaded
        // process (no malloc/opendir), and bionic's close() would run fdsan
        // ownership checks on fds this child does not own.
        closeInheritedFds();

        execve(cmd, argv, envp);
        // execve() only returns on failure.
        _exit(127);
    }

    // Parent.
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(envp);
    (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
    if (jCwd && cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);

    jint pidOut = (jint) pid;
    (*env)->SetIntArrayRegion(env, outPid, 0, 1, &pidOut);
    LOGI("forkpty ok: pid=%d masterFd=%d cols=%d rows=%d", pid, masterFd, cols, rows);
    return masterFd;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_readBytes(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint off, jint len)
{
    if (fd < 0 || len <= 0) return -EINVAL;
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!data) return -ENOMEM;
    ssize_t n = read(fd, data + off, (size_t) len);
    int err = (n < 0) ? errno : 0;
    (*env)->ReleaseByteArrayElements(env, buf, data, 0);
    if (n < 0) return -err;
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_writeBytes(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint off, jint len)
{
    if (fd < 0 || len <= 0) return -EINVAL;
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!data) return -ENOMEM;
    ssize_t written = 0;
    while (written < len) {
        ssize_t n = write(fd, data + off + written, (size_t) (len - written));
        if (n < 0) {
            if (errno == EINTR) continue;
            int err = errno;
            (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
            return -err;
        }
        written += n;
    }
    (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
    return (jint) written;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_setWindowSize(
    JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows)
{
    if (fd < 0) return -EINVAL;
    struct winsize ws;
    ws.ws_col = (unsigned short) cols;
    ws.ws_row = (unsigned short) rows;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    if (ioctl(fd, TIOCSWINSZ, &ws) < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_closeFd(
    JNIEnv *env, jclass clazz, jint fd)
{
    if (fd < 0) return 0;
    if (close(fd) < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_sendSignal(
    JNIEnv *env, jclass clazz, jint pid, jint sig)
{
    if (pid <= 0) return -EINVAL;
    if (kill((pid_t) pid, sig) < 0) return -errno;
    return 0;
}

// Blocking wait for the given child pid. Returns the exit status
// (WEXITSTATUS) on normal exit, or -(128+sig) on termination by signal.
// Returns -errno on waitpid() failure.
JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_waitFor(
    JNIEnv *env, jclass clazz, jint pid)
{
    if (pid <= 0) return -EINVAL;
    int status = 0;
    while (1) {
        pid_t r = waitpid((pid_t) pid, &status, 0);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -errno;
        }
        break;
    }
    if (WIFEXITED(status)) return (jint) WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return (jint) -(128 + WTERMSIG(status));
    return -1;
}

// [T-shell-stall-yes-head] ART blocks SIGPIPE (with SIGQUIT and SIGUSR1) in
// every app thread (Runtime::BlockSignals), and ProcessBuilder's child keeps
// the caller's signal mask across execve. Agent shells spawned that way ran
// with SIGPIPE blocked, so a writer whose reader had exited got EPIPE instead
// of dying: busybox `yes foobar | head -c 50000` then spins forever and the
// tool call hangs until its timeout. forkExec above clears the mask in its
// own child; ProcessBuilder offers no such hook, so the caller unblocks
// SIGPIPE on the spawning thread around start() and restores it afterwards.
// The child inherits that thread's mask. Only SIGPIPE: it is thread-directed
// (raised by this thread's own writes), so the brief window cannot catch a
// process-wide SIGQUIT meant for ART's signal catcher.
//
// Returns a bit set: 1 = SIGPIPE was blocked before (caller must re-block),
// 2 = SIGPIPE's disposition is SIG_IGN (a child would inherit that too, so
// unblocking alone does not help - logged by the caller). -errno on failure.
//
// [T-sigpipe-pending-drain] A SIGPIPE already PENDING on this thread is
// consumed first. The thread is a shared IO-dispatcher thread: an earlier
// write of its own to a broken pipe or socket (a provider upload the server
// dropped, a dead shell's stdin) raised SIGPIPE, which stayed pending while
// blocked. Unblocking would deliver it at once, the disposition is SIG_DFL
// and the app installs no handler, so the whole app would die with no
// exception and no tombstone. While SIGPIPE is still blocked, a zero-timeout
// sigtimedwait takes it off the pending set; this thread makes no write in
// the window, so none can arrive before the re-block.
JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_unblockSigpipe(
    JNIEnv *env, jclass clazz)
{
    sigset_t set, old, pending;
    sigemptyset(&set);
    sigaddset(&set, SIGPIPE);
    if (sigpending(&pending) == 0 && sigismember(&pending, SIGPIPE) == 1) {
        struct timespec zero = { 0, 0 };
        int drained = 0;
        while (drained < 8 && sigtimedwait(&set, NULL, &zero) == SIGPIPE) drained++;
        __android_log_print(ANDROID_LOG_WARN, "PtyBridge",
            "[T-sigpipe-pending-drain] drained %d pending SIGPIPE before unblock", drained);
    }
    int rc = pthread_sigmask(SIG_UNBLOCK, &set, &old);
    if (rc != 0) return -rc;
    jint result = sigismember(&old, SIGPIPE) == 1 ? 1 : 0;
    struct sigaction sa;
    if (sigaction(SIGPIPE, NULL, &sa) == 0 && sa.sa_handler == SIG_IGN) result |= 2;
    return result;
}

// [T-shell-stall-yes-head] Re-block SIGPIPE on the calling thread. 0 or -errno.
JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_blockSigpipe(
    JNIEnv *env, jclass clazz)
{
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGPIPE);
    int rc = pthread_sigmask(SIG_BLOCK, &set, NULL);
    return rc == 0 ? 0 : -rc;
}
