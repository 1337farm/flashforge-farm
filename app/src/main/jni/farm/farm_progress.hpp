#pragma once

// Progress mailbox: a low-contention funnel from the slicing worker(s) to the
// Java UI listener, via a single dedicated pump thread.
//
// Why not call the listener from every status callback? PrusaSlicer fires
// status callbacks from arbitrary oneTBB worker threads. AttachCurrentThread
// per callback (the current farm_native.cpp model_slice approach) is expensive
// and leaks attached threads until detach; calling into Java from a bare TBB
// worker without attaching is UB. Instead:
//
//     slicing worker(s)  --progress_set()-->  mailbox  --pump thread-->  Java
//
// The pump thread attaches to the JVM exactly once per slice session, drains
// the mailbox, and calls SliceListener.onProgress(int, String) on a global ref
// (so the listener outlives the JNI-invoking thread's local ref). Producers
// never block on the UI: progress_set only locks briefly to copy + publish a
// monotonically-increasing sequence number; the pump collapses redundant
// updates and always dispatches the latest snapshot.
//
// Thread-safety contract:
//   - progress_begin / progress_end must be called from the JNI-invoking thread
//     (the thread that holds the local `listener` ref), once per slice.
//   - progress_set may be called from ANY thread, including TBB workers.
//   - A single session is active at a time; begin joins any stale pump first.

#include <jni.h>

namespace farm {

// Start a progress session bound to `listener`. Takes a global ref so the
// listener is kept alive for the whole session. Must be paired with
// progress_end on every exit path (including exceptions).
void progress_begin(JNIEnv* env, JavaVM* vm, jobject listener);

// Publish an update. Fire-and-forget; safe from any thread. `phase` may be
// nullptr (the Java side then receives a null String); it is copied before the
// call returns, so callers may reuse/free their buffer immediately.
void progress_set(int percent, const char* phase);

// Flush and terminate the session: joins the pump thread and releases the
// global ref. Safe to call if begin was never called (no-op).
void progress_end(JNIEnv* env);

// RAII guard so an early return / thrown exception cannot leak the session.
class ScopedProgress {
public:
    ScopedProgress(JNIEnv* env, JavaVM* vm, jobject listener)
        : env_(env) { progress_begin(env, vm, listener); }
    ~ScopedProgress() { progress_end(env_); }
    ScopedProgress(const ScopedProgress&) = delete;
    ScopedProgress& operator=(const ScopedProgress&) = delete;

private:
    JNIEnv* env_;
};

}  // namespace farm