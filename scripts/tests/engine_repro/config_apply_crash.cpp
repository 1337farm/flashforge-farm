// config_apply_crash — headless reproducer for the slice "Assigning an
// incompatible type" ConfigurationError.
//
// Mirrors the exact pre-apply pipeline of com.flashforge.farm.slic3r.Native
// model_slice (app/src/main/jni/farm/farm_native.cpp): load the INI with
// ForwardCompatibilitySubstitutionRule::Disable, normalize_fdm(), the
// curr_bed_type / flush-matrix / multicolor fixups, then Print::apply() — the
// step where the enum set() type-check throws.
//
// Built against a release engine libslic3r.so (see build_harness.sh) and run
// where that .so's platform matches (bionic/aarch64). Exit codes:
//   0  apply succeeded
//   2  a ConfigurationError was thrown (what() printed to stderr)
//   64 usage
//
// Node: print.apply() is the crash site; with the post-#44 engine every enum
// set() type-check names dst <- src C++ classes, so the stderr output must
// NOT be the bare 'ConfigOptionEnumGeneric: Assigning an incompatible type'.

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <memory>
#include <set>
#include <string>
#include <typeinfo>
#include <vector>

#include "libslic3r/Config.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/Format/STL.hpp"

using namespace Slic3r;

namespace {

void log_marker(const char* fmt, ...) {
    std::fprintf(stdout, "[config_apply_crash] ");
    va_list ap;
    va_start(ap, fmt);
    std::vfprintf(stdout, fmt, ap);
    va_end(ap);
    std::fprintf(stdout, "\n");
    std::fflush(stdout);
}

// Same list as farm_native.cpp extraFilamentOpts.
const std::set<std::string>& extra_filament_opts() {
    static const std::set<std::string> opts = {
        "nozzle_temperature", "nozzle_temperature_initial_layer",
        "nozzle_temperature_range_low", "nozzle_temperature_range_high",
        "cool_plate_temp", "cool_plate_temp_initial_layer",
        "eng_plate_temp", "eng_plate_temp_initial_layer",
        "hot_plate_temp", "hot_plate_temp_initial_layer",
        "textured_plate_temp", "textured_plate_temp_initial_layer",
        "chamber_temperature", "chamber_minimal_temperature",
        "full_fan_speed_layer", "bridge_fan_speed", "max_fan_speed", "min_fan_speed",
        "slow_down_layer_time", "fan_below_layer_time",
    };
    return opts;
}

// Mirrors farm_native's per-filament vector resizing (cloneToN).
void clone_to_n(ConfigOption* opt, int n) {
    if (auto* o = dynamic_cast<ConfigOptionFloats*>(opt))                { if (!o->values.empty()) o->values.resize(n, o->values[0]); }
    else if (auto* o = dynamic_cast<ConfigOptionInts*>(opt))             { if (!o->values.empty()) o->values.resize(n, o->values[0]); }
    else if (auto* o = dynamic_cast<ConfigOptionStrings*>(opt))          { if (!o->values.empty()) o->values.resize(n, o->values[0]); }
    else if (auto* o = dynamic_cast<ConfigOptionBools*>(opt))            { if (!o->values.empty()) o->values.resize(n, o->values[0]); }
    else if (auto* o = dynamic_cast<ConfigOptionPercents*>(opt))         { if (!o->values.empty()) o->values.resize(n, o->values[0]); }
    else if (auto* o = dynamic_cast<ConfigOptionFloatsOrPercents*>(opt)) { if (!o->values.empty()) o->values.resize(n, o->values[0]); }
}

void multicolor_fixups(DynamicPrintConfig* config, int numFilaments, const std::vector<int>& palette) {
    config->set_num_filaments((unsigned int) numFilaments);

    for (const std::string& key : config->keys()) {
        bool isFilament = key.rfind("filament_", 0) == 0 || extra_filament_opts().count(key) > 0;
        if (isFilament) {
            if (key == "filament_settings_id" || key == "filament_self_index" || key == "filament_colour" ||
                key == "filament_multi_colour" || key == "filament_map") continue;
            ConfigOption* opt = config->option(key, false);
            if (opt && opt->is_vector()) clone_to_n(opt, numFilaments);
        }
    }

    std::vector<std::string> colorStrs;
    for (int i = 0; i < numFilaments; ++i) {
        int src = (i < (int) palette.size()) ? i : (palette.empty() ? 0 : (int) palette.size() - 1);
        char buf[8];
        std::snprintf(buf, sizeof(buf), "#%06X", (palette.empty() ? 0xFFFFFF : palette[src]) & 0xFFFFFF);
        colorStrs.emplace_back(buf);
    }
    if (!colorStrs.empty()) {
        config->set_key_value("filament_colour", new ConfigOptionStrings(colorStrs));
        config->set_key_value("filament_multi_colour", new ConfigOptionStrings(colorStrs));
        std::vector<std::string> colorTypes(numFilaments, "1");
        config->set_key_value("filament_colour_type", new ConfigOptionStrings(colorTypes));
    }

    std::vector<int> selfIdx(numFilaments);
    for (int i = 0; i < numFilaments; ++i) selfIdx[i] = i + 1;
    config->set_key_value("filament_self_index", new ConfigOptionInts(selfIdx));

    std::vector<std::string> ids;
    for (int i = 0; i < numFilaments; ++i) {
        char buf[32];
        std::snprintf(buf, sizeof(buf), "Filament %d", i + 1);
        ids.emplace_back(buf);
    }
    config->set_key_value("filament_settings_id", new ConfigOptionStrings(ids));

    std::vector<int> filamentMap(numFilaments, 1);
    config->set_key_value("filament_map", new ConfigOptionInts(filamentMap));
}

// Mirrors farm_native's always-run purge/flush normalization: rebuild
// flush_volumes_matrix / flush_multiplier / flush_volumes_vector to the
// engine's expected sizing so _make_wipe_tower never indexes out of bounds.
void normalize_flush(DynamicPrintConfig* config) {
    size_t fc = 0;
    if (auto* o = dynamic_cast<const ConfigOptionStrings*>(config->option("filament_colour", false)))
        fc = o->values.size();
    if (fc > 1) {
        size_t nozzles = 1;
        if (auto* nd = dynamic_cast<const ConfigOptionFloats*>(config->option("nozzle_diameter", false)))
            if (!nd->values.empty()) nozzles = nd->values.size();

        auto* mx = dynamic_cast<const ConfigOptionFloats*>(config->option("flush_volumes_matrix", false));
        if (mx == nullptr || mx->values.size() != fc * fc * nozzles) {
            std::vector<double> matrix(fc * fc * nozzles, 280.);
            for (size_t z = 0; z < nozzles; ++z)
                for (size_t i = 0; i < fc; ++i)
                    matrix[z * fc * fc + i * fc + i] = 0.;
            config->set_key_value("flush_volumes_matrix", new ConfigOptionFloats(matrix));
        }
        auto* fm = dynamic_cast<const ConfigOptionFloats*>(config->option("flush_multiplier", false));
        if (fm == nullptr || fm->values.size() != nozzles)
            config->set_key_value("flush_multiplier", new ConfigOptionFloats(std::vector<double>(nozzles, 0.3)));
        auto* fv = dynamic_cast<const ConfigOptionFloats*>(config->option("flush_volumes_vector", false));
        if (fv == nullptr || fv->values.size() != fc * 2)
            config->set_key_value("flush_volumes_vector", new ConfigOptionFloats(std::vector<double>(fc * 2, 140.)));
    }
}

}  // namespace

static void crash_handler(int sig);

int main(int argc, char** argv) {
    if (getenv("CONFIG_APPLY_BACKTRACE") != nullptr) {
        ::signal(SIGSEGV, crash_handler);
        ::signal(SIGABRT, crash_handler);
    }
    if (argc < 2) {
        std::fprintf(stderr,
            "usage: %s <config.ini> [model.stl] [filaments]\n"
            "       %s <config.ini> --3mf <model.3mf> [filaments]\n"
            "       %s --dump-defaults <out.ini>\n",
            argv[0], argv[0], argv[0]);
        return 64;
    }
    const std::string ini_path = argv[1];

    if (ini_path == "--dump-defaults") {
        if (argc < 3) { std::fprintf(stderr, "usage: %s --dump-defaults <out.ini>\n", argv[0]); return 64; }
        DynamicPrintConfig cfg = DynamicPrintConfig::full_print_config();
        cfg.save(std::string(argv[2]));
        return 0;
    }

    // Direct exercise of ConfigOptionEnumsGenericTempl::set(), the site of the
    // on-device "Assigning an incompatible type" throw (2026-09-09). The fix
    // copies via static_cast on the shared ConfigOptionInts base instead of
    // dynamic_cast, because the two DSOs (libfarm.so / libslic3r.so) each
    // compile their own typeinfo for the template — a cross-boundary
    // dynamic_cast fails even for identical typeid names. Same-DSO this sanity
    // check verifies the copy semantics (incl. the Templ<true>/<false>
    // cross-variant case) survive the change.
    if (ini_path == "--enum-set-check") {
        ConfigOptionEnumsGenericTempl<false> dst_nullable;
        ConfigOptionEnumsGenericTempl<true>  src_variant;   // cross-variant copy
        ConfigOptionEnumsGenericTempl<false> dst_multi;
        src_variant.values = { 1, 2, 3 };
        dst_nullable.values = { 9 };
        dst_multi.values = { 7, 8 };

        ConfigOption* p = &src_variant;
        dst_nullable.set(p);   // ConfigOption* indirection: no static_cast at call
        if (dst_nullable.values != std::vector<int>({1, 2, 3})) {
            std::fprintf(stderr, "REPRO_ERROR enum set cross-variant copy failed\n");
            return 2;
        }
        ConfigOptionEnumsGenericTempl<false> self;
        ConfigOption* q = &self;
        self.values = { 42 };
        dst_multi.set(q);
        if (dst_multi.values != std::vector<int>({42})) {
            std::fprintf(stderr, "REPRO_ERROR enum set same-type copy failed\n");
            return 2;
        }
        log_marker("enum-set-check OK (cross-variant + same-type)");
        return 0;
    }

    // Positional: [model.stl] [filaments]; explicit: --3mf <file> (mirrors the
    // JNI model_read_from_file path), --stl <file>, and --scope 'key=value'
    // (repeatable) which injects a per-OBJECT config entry the same way a
    // Bambu 3MF's model metadata does (DynamicPrintConfig::set_deserialize).
    std::string stl_path;
    std::string mf3_path;
    std::vector<std::pair<std::string, std::string>> scope_entries;
    bool dump_scope = false;
    int num_filaments = 1;
    {
        int pos = 2;
        for (; pos < argc; ++pos) {
            std::string a = argv[pos];
            if (a == "--3mf" && pos + 1 < argc)      { mf3_path = argv[++pos]; continue; }
            if (a == "--stl" && pos + 1 < argc)      { stl_path = argv[++pos]; continue; }
            if (a == "--dump-scope")                 { dump_scope = true; continue; }
            if (a == "--scope" && pos + 1 < argc) {
                std::string kv = argv[++pos];
                size_t eq = kv.find('=');
                if (eq != std::string::npos) scope_entries.emplace_back(kv.substr(0, eq), kv.substr(eq + 1));
                else scope_entries.emplace_back(kv, std::string());
                continue;
            }
            if (a.rfind("--scope=", 0) == 0) {
                std::string kv = a.substr(std::string("--scope=").size());
                size_t eq = kv.find('=');
                if (eq != std::string::npos) scope_entries.emplace_back(kv.substr(0, eq), kv.substr(eq + 1));
                else scope_entries.emplace_back(kv, std::string());
                continue;
            }
            if (a.size() > 4 && (a.rfind(".stl") == a.size() - 4 || a.rfind(".STL") == a.size() - 4)) { stl_path = a; continue; }
            if (a.size() > 4 && (a.rfind(".3mf") == a.size() - 4 || a.rfind(".3MF") == a.size() - 4)) { mf3_path = a; continue; }
            if (a.find_first_not_of("0123456789") == std::string::npos) { num_filaments = std::atoi(a.c_str()); continue; }
            std::fprintf(stderr, "ignoring unexpected arg: %s\n", a.c_str());
        }
    }

    try {
        log_marker("load %s", ini_path.c_str());
        auto print = std::make_unique<Print>();
        auto config = std::make_unique<DynamicPrintConfig>();
        config->load(std::string(ini_path), ForwardCompatibilitySubstitutionRule::Disable);
        log_marker("normalize_fdm");
        config->normalize_fdm();
        log_marker("loaded: %zu keys", config->keys().size());

        if (config->option("curr_bed_type", false) == nullptr) {
            config->set_key_value("curr_bed_type", new ConfigOptionEnum<BedType>(btPEI));
            log_marker("set curr_bed_type = PEI (compiled default slot guard)");
        }

        if (num_filaments > 1) {
            log_marker("multicolor fixups (filaments=%d)", num_filaments);
            std::vector<int> palette(num_filaments, 0xFFFFFF);
            multicolor_fixups(config.get(), num_filaments, palette);
        }

        normalize_flush(config.get());

        Model model;
        if (!mf3_path.empty()) {
            // Mirrors Java_com_flashforge_farm_slic3r_Native_model_read_from_file
            // (app/src/main/jni/farm/farm_native.cpp): for .3mf the load
            // strategy is AddDefaultInstances | LoadModel, and the project
            // config / substitutions pointers are null.
            log_marker("load 3mf %s (strategy=AddDefaultInstances|LoadModel)", mf3_path.c_str());
            model = Model::read_from_file(
                mf3_path, nullptr, nullptr,
                LoadStrategy::AddDefaultInstances | LoadStrategy::LoadModel,
                nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, 0, nullptr);
            log_marker("model loaded: %zu object(s)", model.objects.size());
            for (auto* mo : model.objects) {
                log_marker("  object '%s' volumes=%zu instances=%zu config_keys=%zu",
                    mo->name.c_str(), mo->volumes.size(), mo->instances.size(),
                    mo->config.keys().size());
                for (auto* v : mo->volumes)
                    log_marker("    volume '%s' config_keys=%zu", v->name.c_str(), v->config.keys().size());
            }
        } else if (!stl_path.empty()) {
            if (!load_stl(stl_path.c_str(), &model)) {
                std::fprintf(stderr, "REPRO_ERROR load_stl failed for %s\n", stl_path.c_str());
                return 2;
            }
            log_marker("model loaded: %zu object(s)", model.objects.size());
        } else {
            // A minimal mesh so per-object region configs are exercised too.
            TriangleMesh cube = Slic3r::make_cube(20., 20., 40.);
            ModelObject* obj = model.add_object("box", stl_path.c_str(), std::move(cube));
            obj->center_around_origin();
            log_marker("synthetic box object added: %zu volume(s)", obj->volumes.size());
        }

        // Inject per-object config entries the way a Bambu 3MF's model
        // metadata does (DynamicPrintConfig::set_deserialize, enabling the
        // same substitutions the JNI uses inside Model::read_from_file).
        if (!scope_entries.empty() && model.objects.empty()) {
            std::fprintf(stderr, "REPRO_ERROR no model object for --scope\n");
            return 2;
        }
        if (!scope_entries.empty()) {
            ConfigSubstitutionContext scope_ctxt(ForwardCompatibilitySubstitutionRule::EnableSilent);
            ModelObject* obj = model.objects.front();
            for (auto& kv : scope_entries) {
                log_marker("set_object_config %s = %s", kv.first.c_str(), kv.second.c_str());
                try {
                    obj->config.set_deserialize(kv.first, kv.second, scope_ctxt);
                } catch (const std::exception& e) {
                    log_marker("  set_deserialize FAILED: %s", e.what());
                }
                for (auto& kv2 : scope_ctxt.unrecogized_keys)
                    log_marker("  unrecognized key: %s", kv2.c_str());
                scope_ctxt.unrecogized_keys.clear();
            }
        }

        if (dump_scope) {
            for (auto* mo : model.objects) {
                log_marker("dump object '%s' config (%zu keys):", mo->name.c_str(), mo->config.keys().size());
                for (const auto& k : mo->config.keys()) {
                    const ConfigOption* o = mo->config.option(k);
                    log_marker("  %s type=%d serialize='%s'", k.c_str(),
                        o ? (int) o->type() : -1, o ? o->serialize().c_str() : "?");
                }
                for (auto* v : mo->volumes) {
                    log_marker("  volume '%s' config (%zu keys):", v->name.c_str(), v->config.keys().size());
                    for (const auto& k : v->config.keys()) {
                        const ConfigOption* o = v->config.option(k);
                        log_marker("    %s type=%d serialize='%s'", k.c_str(),
                            o ? (int) o->type() : -1, o ? o->serialize().c_str() : "?");
                    }
                }
            }
        }

        auto config_errors = config->validate();
        if (!config_errors.empty()) {
            for (const auto& kv : config_errors)
                log_marker("config.validate() key=%s msg=%s", kv.first.c_str(), kv.second.c_str());
        }

        log_marker("auto_assign_extruders");
        for (auto* mo : model.objects) print->auto_assign_extruders(mo);
        print->is_BBL_printer() = false;

        if (getenv("CONFIG_APPLY_PROBE_VARIANTS") != nullptr) {
            // Replicates DynamicPrintConfig::update_values_to_printer_extruders'
            // per-key access so the crashing key/type/vector-size is visible.
            const std::set<std::string> pv1 = {
                "nozzle_volume","retraction_length","z_hop","travel_slope",
                "retract_lift_above","retract_lift_below","retract_lift_enforce",
                "z_hop_types","retraction_speed","deretraction_speed",
                "retraction_minimum_travel","retract_when_changing_layer","wipe",
                "wipe_distance","retract_before_wipe","retract_length_toolchange",
                "retract_restart_extra","retract_restart_extra_toolchange",
                "long_retractions_when_cut","retraction_distances_when_cut",
                "nozzle_type","printer_extruder_id","printer_extruder_variant",
                "nozzle_flush_dataset"};
            const ConfigDef* def = config->def();
            int ecount = 0; bool diff = config->support_different_extruders(ecount);
            log_marker("PROBE extruder_count=%d different=%d", ecount, (int) diff);
            for (const auto& key : pv1) {
                const ConfigOptionDef* od = def ? def->get(key) : nullptr;
                const ConfigOption* o = config->option(key);
                std::string ty = "?";
                size_t vsz = 0;
                if (o) { vsz = (size_t) -1; }
                if (auto* v = dynamic_cast<const ConfigOptionFloats*>(o))   { ty="coFloats"; vsz=v->values.size(); }
                if (auto* v = dynamic_cast<const ConfigOptionInts*>(o))     { ty="coInts";   vsz=v->values.size(); }
                if (auto* v = dynamic_cast<const ConfigOptionStrings*>(o))  { ty="coStrings";vsz=v->values.size(); }
                if (auto* v = dynamic_cast<const ConfigOptionBools*>(o))    { ty="coBools";  vsz=v->values.size(); }
                if (auto* v = dynamic_cast<const ConfigOptionPercents*>(o)) { ty="coPerc";   vsz=v->values.size(); }
                if (auto* v = dynamic_cast<const ConfigOptionEnumsGeneric*>(o)) { ty="coEnums"; vsz=v->values.size(); }
                if (o && vsz == (size_t) -1) vsz = 1;
                log_marker("PROBE key=%s def=%s opt=%s type=%s size=%zu",
                    key.c_str(), od ? "yes" : "NO", o ? "yes" : "MISSING", ty.c_str(), vsz);
            }
            const ConfigOption* oe = config->option("extruder_type");
            const ConfigOption* on = config->option("nozzle_volume_type");
            log_marker("PROBE extruder_type opt=%s", oe ? oe->serialize().c_str() : "MISSING");
            log_marker("PROBE nozzle_volume_type opt=%s", on ? on->serialize().c_str() : "MISSING");
        }

        log_marker("apply");
        print->apply(model, *config);
        log_marker("apply OK");

        std::string verr = print->validate().string;
        if (!verr.empty()) {
            log_marker("print.validate() error: %s", verr.c_str());
            std::fprintf(stderr, "REPRO_ERROR print.validate: %s\n", verr.c_str());
            return 2;
        }
        log_marker("validate OK");
        return 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "REPRO_ERROR type=%s what=%s\n", typeid(e).name(), e.what());
        return 2;
    } catch (...) {
        std::fprintf(stderr, "REPRO_ERROR unknown\n");
        return 3;
    }
}
// Optional: run with CONFIG_APPLY_BACKTRACE=1 to dump a backtrace on crash.
#include <execinfo.h>
#include <csignal>
static void crash_handler(int sig) {
    fprintf(stderr, "\nCRASH signal=%d\n", sig);
    void* bt[64];
    int n = backtrace(bt, 64);
    backtrace_symbols_fd(bt, n, 2);
    _exit(128 + sig);
}
