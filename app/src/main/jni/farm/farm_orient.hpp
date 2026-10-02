#pragma once

// Auto-orient entry points implemented in farm_orient.cpp against the genuine
// upstream PrusaSlicer orienter (engine/src/main/jni/compat/Orient.cpp).
// overhang_angle is in degrees (0 = every downward face counts as overhang).

#include <functional>
#include <string>

// tag/percent progress: orient() emits the part index (0-based) when a part
// starts and 20/30/60/80 as the orienter progresses through that part.
// Progress is reported for the first fixpoint pass only (the orienter re-runs
// on the rotated mesh to a stable pose, so later passes are internal).
using FarmOrientProgressFn = std::function<void(unsigned tag, const std::string& name)>;

extern "C" void farm_auto_orient(void* model_object_ptr, void* model_ptr, double overhang_angle);

// Orient several ModelObjects in one engine pass, reporting progress through
// `progress` (in the range 0..count-1 on part start, then 20..80 per part).
// Each object is run to a fixpoint: the same upstream orienter is re-run on
// the rotated mesh until the chosen rotation is ~0, mimicking "tap it again".
//
// `model_ptr` is the owning ModelRef as an opaque key for the concurrency
// guard below; pass it unchanged to farm_orient_cancel / farm_orient_drain.
extern "C" void farm_auto_orient_batch(void* const* model_objects, size_t count,
                                       void* model_ptr, double overhang_angle,
                                       FarmOrientProgressFn progress);

// Concurrency guard for the background orient worker. The worker holds raw
// ModelObject* across a long blocking orient() call, so a concurrent delete or
// model_release() would otherwise be a use-after-free.
//
// farm_orient_cancel: a user mutation (rotate/mirror/split/cut/translate/
// scale) invalidates any in-flight orient. The worker notices at its next
// stage boundary, abandons its candidate rotation and leaves the user's own
// mutation in place (it does NOT restore the pre-orient snapshot, which would
// clobber the mutation that triggered the cancel).
//
// farm_orient_drain: block until no orient is running against this model, then
// return. Callers that free objects (deleteObject, model_release) must use this
// so no worker can be holding a pointer into the model being destroyed.
extern "C" void farm_orient_cancel(void* model_ptr);
extern "C" void farm_orient_drain(void* model_ptr);

// Per-model token the worker holds while it runs. Pass the model pointer so
// cancel/drain can find the same entry; `token` is an opaque generation the
// worker compares against farm_orient_is_cancelled().
extern "C" unsigned long long farm_orient_enter(void* model_ptr);
extern "C" void farm_orient_leave(void* model_ptr);
extern "C" bool farm_orient_is_cancelled(void* model_ptr, unsigned long long token);
