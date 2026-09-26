#pragma once

// Auto-orient entry point implemented in farm_orient.cpp against the genuine
// upstream PrusaSlicer orienter (engine/src/main/jni/compat/Orient.cpp).
// overhang_angle is in degrees (0 = every downward face counts as overhang).
void farm_auto_orient(void* model_object_ptr, double overhang_angle);
