// cfgdump — print what load_config_from_legacy_file actually yields.
//
// Diagnostic companion to slice_repro: when a slice fails, run this first
// to see the loaded values (vs assuming the INI text survived the legacy
// reader). Exit 0 + key dump, 2 on load failure.
//
// Build (bionic/aarch64, needs engine headers + libslic3r.so):
//   clang++ -std=c++20 cfgdump.cpp <engine includes> libslic3r.so \
//     libassert.a libcpptrace.a libgmp.so libgmpxx.so libmpfr.so \
//     -llog -lEGL -lGLESv3 -ldl -lm -lz -o cfgdump
#include <cstdio>
#include <string>
#include <vector>
#include "Slic3r/Biz/Config/ConfigLegacy.hpp"
#include "Slic3r/Domain/ConfigPack.hpp"

namespace Domain = Slic3r::Domain;

template <typename T>
static bool try_print(const Domain::ConfigBox& b, const char* box, const char* key) {
    const Domain::ConfigItem* it = b.items.find(key);
    if (it == nullptr) return false;
    try {
        T v = it->get<T>();
        if constexpr (std::is_same_v<T, bool>) std::printf("%s.%s = %d\n", box, key, (int) v);
        else if constexpr (std::is_same_v<T, std::string>) std::printf("%s.%s = '%s'\n", box, key, v.c_str());
        else std::printf("%s.%s = %f\n", box, key, (double) v);
        return true;
    } catch (...) { return false; }
}

static void show(const Domain::ConfigBox& b, const char* box, const char* key) {
    if (try_print<int>(b, box, key)) return;
    if (try_print<double>(b, box, key)) return;
    if (try_print<bool>(b, box, key)) return;
    if (try_print<std::string>(b, box, key)) return;
    const Domain::ConfigItem* it = b.items.find(key);
    if (it == nullptr) std::printf("%s.%s = <missing>\n", box, key);
    else std::printf("%s.%s = <unprintable-type>\n", box, key);
}

int main(int argc, char** argv) {
    if (argc != 2) return 64;
    Domain::ConfigPack pack;
    try {
        pack = Slic3r::Biz::load_config_from_legacy_file(argv[1]);
    } catch (const std::exception& e) {
        std::printf("LOAD_FAIL %s\n", e.what());
        return 2;
    }
    auto* fdm = std::get_if<Domain::ConfigPackFDM>(&pack);
    if (!fdm) { std::printf("NOT_FDM\n"); return 2; }
    const char* keys[] = {"perimeters", "fill_density", "layer_height",
        "first_layer_height", "top_solid_layers", "bottom_solid_layers",
        "spiral_vase", "complete_objects", "support_material",
        "perimeter_extruder", "infill_extruder", "brim_type", "skirts",
        "skirt_distance", "min_layer_height", "max_layer_height"};
    for (auto k : keys) show(fdm->print, "print", k);
    show(fdm->printer, "printer", "bed_shape");
    show(fdm->printer, "printer", "max_print_height");
    // nozzle_diameter is a vector opt: report element count + first value.
    {
        const Domain::ConfigItem* it = fdm->printer.items.find("nozzle_diameter");
        if (it == nullptr) std::printf("printer.nozzle_diameter = <missing>\n");
        else {
            try {
                auto v = it->get<std::vector<double>>();
                std::printf("printer.nozzle_diameter[%zu] first=%f\n", v.size(), v.empty() ? -1 : v[0]);
            } catch (...) { std::printf("printer.nozzle_diameter = <unprintable>\n"); }
        }
    }
    // bed_shape contour size.
    {
        const Domain::ConfigItem* it2 = fdm->printer.items.find("bed_shape");
        if (it2 != nullptr) {
            try {
                auto c = it2->get<Slic3r::Domain::Vec2ds>();
                std::printf("printer.bed_shape points=%zu\n", c.size());
            } catch (...) { std::printf("printer.bed_shape = <unprintable>\n"); }
        }
    }
    std::printf("tools=%zu filaments=%zu\n", fdm->tool.size(), fdm->filament.size());
    return 0;
}
