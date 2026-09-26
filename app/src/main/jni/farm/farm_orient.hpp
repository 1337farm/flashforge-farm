#pragma once

// Auto-orient entry points implemented in farm_orient.cpp against the genuine
// upstream PrusaSlicer orienter (engine/src/main/jni/compat/Orient.cpp).
// overhang_angle is in degrees (0 = every downward face counts as overhang).

#include <functional>
#include <string>

// tag/percent progress: orient() emits the part index (0-based) when a part
// starts and 20/30/60/80 as the orienter progresses through that part.
using FarmOrientProgressFn = std::function<void(unsigned tag, const std::string& name)>;

extern "C" void farm_auto_orient(void* model_object_ptr, double overhang_angle);

// Orient several ModelObjects in one engine pass, reporting progress through
// `progress` (in the range 0..count-1 on part start, then 20..80 per part).
extern "C" void farm_auto_orient_batch(void* const* model_objects, size_t count,
                                       double overhang_angle,
                                       FarmOrientProgressFn progress);
