// Auto-orient wrapper around the genuine upstream PrusaSlicer orienter.
//
// Isolated in its own translation unit (like farm_progress.cpp) for one reason:
// Orient.hpp transitively includes libslic3r/Point.hpp -> libslic3r.h, which
// defines a global SCALED_EPSILON that collides with Slic3r::Domain's constant
// in any TU that also uses render/bed_utils.hpp. Keeping the include out of
// farm_native.cpp avoids that clash entirely.

#include "farm_orient.hpp"

#include <android/log.h>

#include "Slic3r/Biz/Algorithms/ModelObject.hpp"
#include "Slic3r/Biz/Algorithms/TriangleMesh.hpp"

#include "Orient.hpp"

#include <cmath>
#include <condition_variable>
#include <exception>
#include <mutex>
#include <unordered_map>
#include <vector>

#define LOG_TAG "FarmOrient"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Concurrency registry.
//
// The orient worker runs on a background thread and holds raw ModelObject*
// across a long blocking orient() call. Model mutations arrive on the UI
// thread with no synchronization at all, so:
//
//   * a rotate/mirror/translate that lands mid-run races the worker's own
//     transform write, and the loser's result silently wins;
//   * deleteObject()/model_release() while a worker holds a pointer is a
//     use-after-free.
//
// Fix: a small per-model record with a cancel generation and an active-worker
// count. Mutations bump the generation (worker aborts at its next checkpoint
// and keeps the user's result); frees drain the count to zero first.
namespace {

struct OrientSlot {
    unsigned long long generation = 1; // bumped by farm_orient_cancel
    int active = 0;                    // workers currently inside orient
};

std::mutex g_orient_mutex;
std::condition_variable g_orient_cv;
std::unordered_map<void*, OrientSlot> g_orient_slots;

} // namespace

extern "C" unsigned long long farm_orient_enter(void* model_ptr) {
    std::lock_guard<std::mutex> lock(g_orient_mutex);
    OrientSlot& slot = g_orient_slots[model_ptr];
    ++slot.active;
    return slot.generation;
}

extern "C" void farm_orient_leave(void* model_ptr) {
    std::lock_guard<std::mutex> lock(g_orient_mutex);
    auto it = g_orient_slots.find(model_ptr);
    if (it == g_orient_slots.end()) return;
    if (--it->second.active <= 0) g_orient_slots.erase(it);
    g_orient_cv.notify_all();
}

extern "C" bool farm_orient_is_cancelled(void* model_ptr, unsigned long long token) {
    std::lock_guard<std::mutex> lock(g_orient_mutex);
    auto it = g_orient_slots.find(model_ptr);
    // A vanished slot means the model was torn down underneath us; treat that
    // as cancellation so the worker bails instead of writing to freed memory.
    if (it == g_orient_slots.end()) return true;
    return it->second.generation != token;
}

extern "C" void farm_orient_cancel(void* model_ptr) {
    if (model_ptr == nullptr) return;
    std::lock_guard<std::mutex> lock(g_orient_mutex);
    OrientSlot& slot = g_orient_slots[model_ptr];
    ++slot.generation;
}

extern "C" void farm_orient_drain(void* model_ptr) {
    if (model_ptr == nullptr) return;
    std::unique_lock<std::mutex> lock(g_orient_mutex);
    g_orient_cv.wait(lock, [model_ptr] {
        auto it = g_orient_slots.find(model_ptr);
        return it == g_orient_slots.end() || it->second.active == 0;
    });
}

// Apply an upstream-orienter result as the object's CANONICAL pose. The
// orienter's candidate directions are face/hull normals of the CURRENT mesh,
// and its best orientation is scored against that same posed mesh, so feeding
// it a user-rotated object both changes the candidate set and (when the chosen
// rotation is composed on top of the existing one) produces a different final
// stance — a model lying on a corner instead of its proper face. To make the
// result a pure function of the geometry, orient_object first strips every
// volume's rotation; the mesh supplied to the orienter is then the object's
// own shape, and here we REPLACE the volume rotation with the chosen one while
// keeping the placement (offset) and scale intact.
static void apply_canonical_orientation(Slic3r::Domain::ModelObject* obj,
                                        const Slic3r::orientation::OrientMesh& oriented)
{
    if (obj == nullptr || !oriented.rotation_matrix.allFinite()) return;
    const Slic3r::Domain::Transform3d rotation_matrix =
        Slic3r::Domain::Transform3d(oriented.rotation_matrix);
    for (Slic3r::Domain::ModelVolume* vol : obj->volumes) {
        if (vol == nullptr) continue;
        const Slic3r::Domain::Transformation& t = vol->get_transformation();
        vol->set_transformation(t.get_offset_matrix() * t.get_scaling_factor_matrix() *
                                rotation_matrix);
    }
    obj->invalidate_bounding_box();
    Slic3r::Biz::Algorithms::ModelObject::ensure_on_bed(*obj, false);
}

// Orient one object with the genuine upstream minimizer against its OWN
// geometry. The orienter's candidate directions come from the CURRENT mesh's
// face/hull normals, so any pose-dependent run (single or iterated) can settle
// on a different face; strip the volumes' rotation first so the candidates and
// scores describe the shape itself, then apply the chosen rotation as the new
// canonical pose. Progress reports the 20/30/60/80 per-stage markers (part
// index < 20 on part start) so the background run stays visible to the UI.
//
// App-side no-op policy: the minimizer's best stance is applied only when it
// is clearly better than the stance the user is CURRENTLY standing in (the
// rotation they actually arranged). Otherwise the model is returned untouched
// -- we never thrash an already-fine pose (a ball or a symmetric badge is left
// exactly as the user posed it). Any failure restores the original stance too.
//
// Cancellation: `model_ptr` + `token` identify this run in the concurrency
// registry above. If a user mutation lands while orient() is running, the
// checkpoints below bail out. On cancellation we deliberately do NOT call
// restore(): the mutation that triggered the cancel has already written the
// user's own transform, and restoring the pre-orient snapshot would silently
// undo it (re-introducing the very overwrite this guards against).
static void orient_object(Slic3r::Domain::ModelObject* obj, double overhang_angle,
                          const FarmOrientProgressFn& progress, unsigned part_index,
                          void* model_ptr, unsigned long long token)
{
    if (obj == nullptr) return;

    // Snapshot the user's arrangement so a no-op or a failure restores it
    // exactly instead of leaving the volumes stripped.
    std::vector<Slic3r::Domain::Transformation> original;
    original.reserve(obj->volumes.size());
    for (Slic3r::Domain::ModelVolume* vol : obj->volumes) {
        if (vol != nullptr) original.push_back(vol->get_transformation());
    }
    const auto restore = [obj, &original]() {
        size_t k = 0;
        for (Slic3r::Domain::ModelVolume* vol : obj->volumes) {
            if (vol == nullptr) continue;
            if (k < original.size()) vol->set_transformation(original[k]);
            ++k;
        }
        obj->invalidate_bounding_box();
    };

    // The mesh-space direction the current (user-arranged) stance has pointing
    // at the bed. For an untouched model this is the shape's file pose; for a
    // manually rotated one it is that rotation's world-up mapped back into the
    // shape's local coordinates, so "don't touch it" means "don't touch THIS
    // stance", not "don't touch the file pose".
    Slic3r::Domain::Vec3d requested_up(0, 0, 1);
    if (!obj->volumes.empty() && obj->volumes.front() != nullptr) {
        const Slic3r::Domain::Vec3d r = obj->volumes.front()->get_rotation();
        const Eigen::Quaterniond q_cur =
            Eigen::AngleAxisd(r[2], Eigen::Vector3d::UnitZ()) *
            Eigen::AngleAxisd(r[1], Eigen::Vector3d::UnitY()) *
            Eigen::AngleAxisd(r[0], Eigen::Vector3d::UnitX());
        requested_up = q_cur.conjugate() * Slic3r::Domain::Vec3d(0, 0, 1);
    }

    // A user mutation (or a teardown) landing mid-run invalidates this pass.
    const auto cancelled = [model_ptr, token]() {
        return farm_orient_is_cancelled(model_ptr, token);
    };

    for (Slic3r::Domain::ModelVolume* vol : obj->volumes) {
        if (vol == nullptr) continue;
        const Slic3r::Domain::Transformation& t = vol->get_transformation();
        vol->set_transformation(t.get_offset_matrix() * t.get_scaling_factor_matrix());
    }

    // The strip above removed the user's rotation; if we bail before orient()
    // has produced a result we must put it back, because no user mutation has
    // happened yet to supersede it.
    if (cancelled()) {
        restore();
        LOGD("farm_orient: cancelled before minimizer (concurrent mutation)");
        return;
    }

    try {
        const Slic3r::Domain::TriangleMesh mesh =
            Slic3r::Biz::Algorithms::ModelObject::mesh(*obj);
        if (mesh.its.vertices.empty() || mesh.its.indices.empty()) {
            restore();
            LOGE("farm_orient: no mesh (instances=%zu volumes=%zu)",
                 obj->instances.size(), obj->volumes.size());
            return;
        }

        Slic3r::orientation::OrientMesh om;
        om.mesh = mesh;
        om.overhang_angle = overhang_angle;
        om.name = obj->name;
        om.requested_up = requested_up;
        om.setter = [obj](const Slic3r::orientation::OrientMesh& o) { apply_canonical_orientation(obj, o); };
        Slic3r::orientation::OrientMeshs items{om};

        // The public orient() entry calls progressfn(i, name) unconditionally
        // per item; with the default empty std::function that throws
        // std::bad_function_call and the whole orient silently no-ops. Hand in
        // a callable hook instead.
        Slic3r::orientation::OrientParams params;
        params.progressind = [progress, part_index](unsigned tag, const std::string& name) {
            if (progress) progress(tag < 20 ? part_index : tag, name);
        };
        // Let the minimizer bail out at its own stage boundaries once a
        // concurrent mutation has invalidated this pass. Without this the
        // worker keeps scoring candidates it will never be allowed to apply.
        params.stopcondition = [cancelled]() { return cancelled(); };
        Slic3r::orientation::orient(items, {}, params);

        // orient() may have aborted via stopcondition, leaving a partial
        // solution. A cancelled run keeps whatever the user just did instead of
        // restoring the snapshot (which would undo their mutation).
        if (cancelled()) {
            LOGD("farm_orient: cancelled after minimizer (concurrent mutation)");
            return;
        }

        const Slic3r::orientation::OrientMesh& res = items.front();
        if (!res.orientation.allFinite() || res.orientation.norm() < 1e-6 ||
            !res.rotation_matrix.allFinite()) {
            restore();
            LOGD("farm_orient: degenerate solution, leaving orientation unchanged");
            return;
        }

        // Only replace the user's stance when the minimizer is clearly better
        // (at least 15% lower unprintability). Otherwise put their pose back
        // and do nothing -- a symmetric model or a ball must not be re-spun.
        const float cur = std::fmax(res.current_unprintability, 0.0f);
        const float best = std::fmax(res.unprintability, 0.0f);
        const bool clearly_better = cur > best && (cur - best) > 0.15f * cur;
        if (!clearly_better) {
            restore();
            LOGD("farm_orient: current stance near-optimal (cur=%.4f best=%.4f) - untouched",
                 cur, best);
            return;
        }

        const double rot_deg = std::abs(res.angle) * 180.0 / std::acos(-1.0);
        res.apply();
        LOGD("farm_orient: dir=(%+.4f, %+.4f, %+.4f) rot=%.2fdeg facets=%zu (cur=%.4f best=%.4f)",
             res.orientation.x(), res.orientation.y(), res.orientation.z(),
             rot_deg, mesh.its.indices.size(), cur, best);
    } catch (const std::exception& e) {
        restore();
        LOGE("farm_orient: orient failed: %s", e.what());
    }
}

// `model_ptr` is the owning ModelRef (cast to void*). It is only used as a
// registry key for the concurrency guard, so a user mutation elsewhere in the
// model can cancel this run before it writes.
extern "C" void farm_auto_orient(void* model_object_ptr, void* model_ptr, double overhang_angle)
{
    if (model_object_ptr == nullptr) return;
    const unsigned long long token = farm_orient_enter(model_ptr);
    orient_object(static_cast<Slic3r::Domain::ModelObject*>(model_object_ptr),
                  overhang_angle, FarmOrientProgressFn(), 0, model_ptr, token);
    farm_orient_leave(model_ptr);
}

extern "C" void farm_auto_orient_batch(void* const* raw_objects, size_t count,
                                       void* model_ptr, double overhang_angle,
                                       FarmOrientProgressFn progress)
{
    if (raw_objects == nullptr || count == 0) return;
    std::vector<Slic3r::Domain::ModelObject*> objects;
    for (size_t k = 0; k < count; ++k) {
        if (raw_objects[k] == nullptr) continue;
        objects.push_back(static_cast<Slic3r::Domain::ModelObject*>(raw_objects[k]));
    }
    const unsigned long long token = farm_orient_enter(model_ptr);
    for (size_t j = 0; j < objects.size(); ++j) {
        if (farm_orient_is_cancelled(model_ptr, token)) {
            LOGD("farm_auto_orient_batch: cancelled after %zu of %zu (concurrent mutation)",
                 j, objects.size());
            break;
        }
        orient_object(objects[j], overhang_angle, progress, (unsigned) j, model_ptr, token);
        LOGD("farm_auto_orient_batch: object %zu of %zu converged", j + 1, objects.size());
    }
    farm_orient_leave(model_ptr);
}
