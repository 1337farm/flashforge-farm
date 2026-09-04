#include <android/log.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include <sys/types.h>
#include <sys/syscall.h>
#include <elf.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <link.h>
#include <unwind.h>
#include <ucontext.h>

#include <jni.h>

#define CD_TAG "FarmCrash"
#define MAX_FRAMES 128

// Crash log directory (set from Java). Plain C buffer so the signal handler can
// use it without allocation, published with a release barrier so the value is
// visible to the handler on the crashing thread.
static char g_crash_dir[4096];
static volatile sig_atomic_t g_crash_dir_ready = 0;
// Pre-built metadata header (build id / device / app version) set from Java via
// set_crash_metadata(). Prepended to every native crash dump so the log carries
// enough context to pin down the exact build from a bare device.
static char g_metadata[2048];

// Guard against recursive/repeated signals while we are dumping.
static volatile sig_atomic_t g_dumping = 0;

static const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV (invalid memory access)";
        case SIGABRT: return "SIGABRT (abort)";
        case SIGBUS:  return "SIGBUS (bus error)";
        case SIGILL:  return "SIGILL (illegal instruction)";
        case SIGFPE:  return "SIGFPE (floating point exception)";
        default:      return "SIGNAL";
    }
}

// Human-readable description of the signal's code (si_code), so the dump tells
// you whether a SIGSEGV was a missing-page vs. protection error, SIGBUS a
// non-aligned access, SIGABRT an abort with/without a core, etc.
static const char* signal_code_desc(int sig, int code) {
    switch (sig) {
        case SIGSEGV:
            switch (code) {
                case 0:            return "si_code=0 (handler?)";
                case SEGV_MAPERR:  return "SEGV_MAPERR (address not mapped)";
                case SEGV_ACCERR:  return "SEGV_ACCERR (invalid permissions on mapped region)";
#ifdef SEGV_BNDERR
                case SEGV_BNDERR:  return "SEGV_BNDERR (bounds checker)";
#endif
                default:           return "SEGV (other)";
            }
        case SIGBUS:
            if (code == BUS_ADRALN) return "BUS_ADRALN (misaligned address)";
            if (code == BUS_ADRERR) return "BUS_ADRERR (nonexistent physical address)";
            return "BUS (other)";
        case SIGABRT:
            if (code == SI_TKILL) return "abort()/raise via tkill (explicit abort)";
            return "SIGABRT";
        case SIGILL:
            if (code == ILL_ILLOPC) return "ILL_ILLOPC (illegal opcode)";
            if (code == ILL_ILLOPN) return "ILL_ILLOPN (illegal operand)";
            if (code == ILL_ILLADR) return "ILL_ILLADR (illegal addressing mode)";
            if (code == ILL_PRVOPC) return "ILL_PRVOPC (privileged opcode)";
            return "SIGILL";
        case SIGFPE:
            if (code == FPE_INTDIV) return "FPE_INTDIV (integer divide by zero)";
            if (code == FPE_INTOVF) return "FPE_INTOVF (integer overflow)";
            if (code == FPE_FLTDIV) return "FPE_FLTDIV (float divide by zero)";
            return "SIGFPE";
        default: {
            static char buf[32];
            snprintf(buf, sizeof(buf), "si_code=%d", code);
            return buf;
        }
    }
}

// Wall-clock timestamp as YYYY-MM-DD HH:MM:SS UTC. Not async-signal-safe on
// every libc, but acceptable for a best-effort crash dump (already using
// dl_iterate_phdr + _Unwind_Backtrace which are not strictly safe either).
static inline void write_all(int fd, const void* buf, size_t len);
static void write_timestamp(int fd) {
    struct timespec ts;
    if (clock_gettime(CLOCK_REALTIME, &ts) == 0) {
        struct tm tmv;
        time_t s = ts.tv_sec;
        gmtime_r(&s, &tmv);
        char buf[64];
        int n = snprintf(buf, sizeof(buf),
                         "time    : %04d-%02d-%02d %02d:%02d:%02d UTC\n",
                         tmv.tm_year + 1900, tmv.tm_mon + 1, tmv.tm_mday,
                         tmv.tm_hour, tmv.tm_min, tmv.tm_sec);
        if (n > 0) write_all(fd, buf, (size_t) n);
    }
}

// Process uptime in seconds since app start (CLOCK_MONOTONIC).
static void write_uptime(int fd) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) {
        char buf[64];
        double up = (double) ts.tv_sec + (double) ts.tv_nsec / 1e9;
        int n = snprintf(buf, sizeof(buf), "uptime  : %.3f s (monotonic since process start)\n", up);
        if (n > 0) write_all(fd, buf, (size_t) n);
    }
}

// Kernel thread name for the given tid (from /proc). Best-effort.
static void write_thread_name(int fd, long tid) {
    char proc[64];
    snprintf(proc, sizeof(proc), "/proc/self/task/%ld/comm", (long) tid);
    int cf = open(proc, O_RDONLY);
    if (cf >= 0) {
        char name[64];
        ssize_t n = read(cf, name, sizeof(name) - 1);
        close(cf);
        if (n > 0) {
            name[n] = '\0';
            char* nl = strchr(name, '\n');
            if (nl) *nl = '\0';
            char buf[96];
            int b = snprintf(buf, sizeof(buf), "thread  : %ld (\"%s\")\n", tid, name);
            if (b > 0) write_all(fd, buf, (size_t) b);
        }
    }
}

static inline void write_all(int fd, const void* buf, size_t len) {
    const char* p = (const char*) buf;
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, p + off, len - off);
        if (n <= 0) break;
        off += (size_t) n;
    }
}

// Given a runtime address, find the shared object that maps it and compute the
// offset into that object (suitable for addr2line against the unstripped .so).
// dl_iterate_phdr callback as a C callback; state carried via the struct below.
struct FindData {
    _Unwind_Ptr ip;
    bool found;
    long offset;          // ip - dlpi_addr (address as if .so loaded at 0)
    const char* name;
};

static int find_cb(struct dl_phdr_info* info, size_t size, void* data) {
    FindData* fd = (FindData*) data;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        uintptr_t start = (uintptr_t) info->dlpi_addr + ph->p_vaddr;
        uintptr_t end = start + ph->p_memsz;
        if (fd->ip >= start && fd->ip < end) {
            fd->found = true;
            // For shared objects with the first PT_LOAD at vaddr 0 this equals
            // the symbol's file (runtime-0) address.
            fd->offset = (long) (fd->ip - (uintptr_t) info->dlpi_addr);
            fd->name = info->dlpi_name;
            return 1;
        }
    }
    return 0;
}

// Trace callback for _Unwind_Backtrace. Writes each frame as module+offset.
// Frames 0-1 are the signal trampoline / handler entry, so skip them.
struct TraceState {
    int fd;
    int written;
    int emitted;
    char line[160];
};

static _Unwind_Reason_Code trace_fn(struct _Unwind_Context* ctx, void* arg) {
    TraceState* st = (TraceState*) arg;
    st->emitted++;
    if (st->emitted < 3) return _URC_NO_REASON;
    if (st->written >= MAX_FRAMES) return _URC_END_OF_STACK;
    _Unwind_Ptr ip = _Unwind_GetIP(ctx);

    FindData fdata;
    memset(&fdata, 0, sizeof(fdata));
    fdata.ip = ip;
    if (dl_iterate_phdr(find_cb, &fdata) && fdata.found) {
        int n = snprintf(st->line, sizeof(st->line), "  #%02d %s+0x%lx  (0x%012lx)\n",
                         st->written, fdata.name, fdata.offset, (unsigned long) ip);
        if (n > 0) write_all(st->fd, st->line, (size_t) n);
    } else {
        int n = snprintf(st->line, sizeof(st->line), "  #%02d 0x%012lx (module unknown)\n",
                         st->written, (unsigned long) ip);
        if (n > 0) write_all(st->fd, st->line, (size_t) n);
    }
    st->written++;
    return _URC_NO_REASON;
}

// Collect "name @ load-base" lines for every loaded shared object into a
// caller-provided buffer (best-effort; dl_iterate_phdr is not technically
// async-signal-safe but is standard practice in crash dumps).
struct LibBuf { char* out; size_t cap; size_t len; };
static int lib_cb(struct dl_phdr_info* info, size_t size, void* data) {
    (void) size;
    LibBuf* b = (LibBuf*) data;
    const char* name = (info->dlpi_name && info->dlpi_name[0]) ? info->dlpi_name : "[main executable]";
    int n = snprintf(b->out + b->len, b->cap - b->len, "  %-32s @ 0x%012lx\n",
                     name, (unsigned long) info->dlpi_addr);
    if (n > 0 && (size_t) n < b->cap - b->len) b->len += (size_t) n;
    return 0;
}
static void collect_libs(char* out, size_t cap) {
    LibBuf b = { out, cap, 0 };
    out[0] = '\0';
    dl_iterate_phdr(lib_cb, &b);
}

static void crash_handler(int sig, siginfo_t* info, void* ctx) {
    (void) ctx;
    if (__sync_lock_test_and_set(&g_dumping, 1) != 0) {
        signal(sig, SIG_DFL);
        raise(sig);
        _exit(128 + sig);
    }

    char path[4608];
    int path_len;
    if (g_crash_dir_ready && g_crash_dir[0] != '\0') {
        path_len = snprintf(path, sizeof(path), "%s/farm_crash_%d.log", g_crash_dir, (int) getpid());
    } else {
        path_len = snprintf(path, sizeof(path), "/data/data/com.flashforge.farm/files/farm_crash_%d.log", (int) getpid());
    }
    if (path_len < 0 || (size_t) path_len >= sizeof(path)) {
        path_len = (int) sizeof(path) - 1;
    }

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        signal(sig, SIG_DFL);
        raise(sig);
        _exit(128 + sig);
    }

    // Capture the faulting thread's registers from the ucontext delivered to
    // the handler (pc/lr/sp/fp are the crash site). Fall back to 0 on non-arm.
    uintptr_t pc = 0, lr = 0, sp = 0, fp = 0;
    ucontext_t* uc = (ucontext_t*) ctx;
    if (uc != nullptr) {
#if defined(__aarch64__)
        pc = (uintptr_t) uc->uc_mcontext.pc;
        lr = (uintptr_t) uc->uc_mcontext.regs[30];
        sp = (uintptr_t) uc->uc_mcontext.sp;
        fp = (uintptr_t) uc->uc_mcontext.regs[29];
#elif defined(__arm__)
        pc = (uintptr_t) uc->uc_mcontext.arm_pc;
        lr = (uintptr_t) uc->uc_mcontext.arm_lr;
        sp = (uintptr_t) uc->uc_mcontext.arm_sp;
        fp = (uintptr_t) uc->uc_mcontext.arm_fp;
#endif
    }
    char lib_map[8192];
    collect_libs(lib_map, sizeof(lib_map));

    char header[1400];
    write_timestamp(fd);
    write_uptime(fd);
    write_thread_name(fd, (long) syscall(SYS_gettid));
    int hl = snprintf(header, sizeof(header),
        "signal  : %s (%d)  %s\n"
        "fault   : 0x%lx\n"
        "pid/tid : %d / %ld\n"
        "build   : %s\n"
        "abi     : %s\n"
        "====================================\n"
        "registers: pc=0x%012lx lr=0x%012lx sp=0x%012lx fp=0x%012lx\n"
        "====================================\n"
        "loaded libraries (name @ load-base):\n%s"
        "====================================\n"
        "backtrace (module+offset; addr2line against matching unstripped build):\n",
        signal_name(sig), sig, info ? signal_code_desc(sig, info->si_code) : "?",
        (unsigned long) (info ? (uintptr_t) info->si_addr : 0),
        (int) getpid(), (long) syscall(SYS_gettid),
        g_metadata[0] ? g_metadata : "unknown",
#if defined(__aarch64__)
        "arm64-v8a",
#elif defined(__arm__)
        "armeabi-v7a",
#else
        "unknown",
#endif
        (unsigned long) pc, (unsigned long) lr, (unsigned long) sp, (unsigned long) fp,
        lib_map);
    if (hl > 0 && (size_t) hl <= sizeof(header)) write_all(fd, header, (size_t) hl);

    TraceState st;
    st.fd = fd; st.written = 0; st.emitted = 0;
    _Unwind_Backtrace(trace_fn, &st);

    write_all(fd, "====================================\n", 38);
    close(fd);

    __android_log_print(ANDROID_LOG_FATAL, CD_TAG,
        "Native crash: %s sig=%d si_code=%d — dumped %d frames to %s",
        signal_name(sig), sig, info ? info->si_code : 0, st.written, path);

    signal(sig, SIG_DFL);
    raise(sig);
    _exit(128 + sig);
}

static void install_handler(int sig) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND;
    sigemptyset(&sa.sa_mask);
    sigaction(sig, &sa, nullptr);
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_flashforge_farm_slic3r_Native_set_1crash_1log_1dir(JNIEnv* env, jclass, jstring dir) {
    const char* d = env->GetStringUTFChars(dir, JNI_FALSE);
    if (d != nullptr) {
        strncpy(g_crash_dir, d, sizeof(g_crash_dir) - 1);
        g_crash_dir[sizeof(g_crash_dir) - 1] = '\0';
        g_crash_dir_ready = 1;
        env->ReleaseStringUTFChars(dir, d);
    }

    install_handler(SIGSEGV);
    install_handler(SIGABRT);
    install_handler(SIGBUS);
    install_handler(SIGILL);
    install_handler(SIGFPE);

    __android_log_print(ANDROID_LOG_INFO, CD_TAG,
        "Crash handler installed; dump dir = %s", g_crash_dir);
}

// Store a pre-built metadata header (app version, build id, device model, SDK)
// so native crash dumps carry enough context to pin down the exact build from a
// bare device without needing separate release notes.
JNIEXPORT void JNICALL
Java_com_flashforge_farm_slic3r_Native_set_1crash_1metadata(JNIEnv* env, jclass, jstring meta) {
    const char* m = env->GetStringUTFChars(meta, JNI_FALSE);
    if (m != nullptr) {
        strncpy(g_metadata, m, sizeof(g_metadata) - 1);
        g_metadata[sizeof(g_metadata) - 1] = '\0';
        env->ReleaseStringUTFChars(meta, m);
    }
}

} // extern "C"
