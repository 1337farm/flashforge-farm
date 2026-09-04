# Build Status

The native engine (`libslic3r.so`) and its dependencies (Boost, oneTBB, OCCT,
GMP/MPFR) are **always built from source** — there is no vendored/prebuilt
native binary in this repository.

## GitHub Actions (full build)

The source build runs on GitHub Actions and produces a debug APK:

```bash
# on: workflow_dispatch / push / pull_request
.github/workflows/native-engine-build.yml
```

Chained jobs, each under the runner time limit:

1. `build-deps`    — compiles Boost + oneTBB + OCCT in parallel (cached)
2. `build-engine`  — compiles `libslic3r.so` from `app/src/main/jni/**` via NDK (cached)
3. `package-apk`   — assembles the final debug APK with the source-built `.so`

The final APK is uploaded as the `FlashForgeFarm-Debug-APK` artifact.

The heavy jobs are ccache/keyed caches: on a cache hit the build steps are
skipped, so re-runs are fast after the first full build.

## Commands

Configuration check:

```bash
./gradlew projects --no-daemon --stacktrace
```

Source-native debug build wrapper:

```bash
scripts/build-debug.sh
```

This runs `./gradlew :app:assembleDebug`. Native compilation is driven by
CMake (`externalNativeBuild`) and requires the source-built dependencies to be
present under `app/src/main/jniImports/` and `app/src/main/occt/`.

### Dependency health check

```bash
scripts/check-native-prebuilts.py arm64-v8a
```

This verifies that CMake's expected dependency files (oneTBB, Boost, OCCT,
GMP/GMPXX/MPFR) are present at the paths referenced by `app/CMakeLists.txt`.
Run it after staging dependencies before assembling an APK locally.

## Local iteration (CI + local hybrid)

Because NDK 23.x ships only an x86_64 host toolchain and this workspace runs on
an aarch64 device, the heavy Boost/oneTBB/OCCT + `libslic3r.so` compiles run on
GitHub Actions. For fast local iteration:

1. Build the native engine on GitHub CI and download the `libslic3r` artifact
   (and, on first set-up, the `native-deps` artifact).
2. Stage the `.so` into `app/src/main/occt/jniLibs/arm64-v8a/libslic3r.so` and
   copy the dependency libs into `app/src/main/jniLibs/` + `app/src/main/occt/`.
3. Assemble locally, skipping a redundant native rebuild:

```bash
./gradlew assembleDebug -PskipNativeRebuild=true
```

`-PskipNativeRebuild=true` only skips the redundant native recompile when a
`.so` has already been staged; it never points at a vendored binary. Without it
the project builds the engine from source.
