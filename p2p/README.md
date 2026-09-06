# p2p/ — custom Iroh binding, built from source

`farm-iroh` is our own UniFFI cdylib over `iroh 1.1` (+ `iroh-base`), built
in-repo for Android arm64-v8a. Rationale:

- The published `computer.iroh:iroh` Maven artifacts ship desktop natives
  only (verified: no Android ABIs in 1.0.0 or 1.1.0) — unusable on-device.
- n0's FFI surface exposes endpoints/streams only; we also need blobs and
  gossip, so a custom surface is required regardless.
- Custom UDL = minimal attack/perf surface: endpoint lifecycle only in v1
  (blobs/gossip follow the same pattern). Sync functions over an internal
  `block_on` tokio runtime — nothing async crosses FFI.
- Bulk bytes never cross JNA (sockets live in Rust); JNA carries control
  calls only, so FFI overhead is negligible.

## Layout

- `Cargo.toml` — crate + pinned deps (iroh 1.1, iroh-base 1.1, tokio 1, uniffi 0.32)
- `build.rs` — uniffi scaffolding from `farm_iroh.udl`
- `src/lib.rs` — implementation
- `farm_iroh.udl` — the FFI contract (review this first)
- `output/<ABI>/libfarm_iroh.so` — build output (gitignored, packaged via jniLibs)
- `gen/` — generated Kotlin sources (gitignored, regenerated every build)
- `.cargo/` — generated NDK linker config (gitignored, NDK path varies)

## Build

`scripts/build_farm_iroh_android.sh` (rustup + target + cargo build +
uniffi-bindgen). Rust deps come from crates.io at build time; `cargo vendor`
hermetic sealing is a follow-up if reproducibility demands it.

## Android lifecycle

Bind on foreground, `shutdown()` + persist `secret_key_bytes()` on
backgrounding (see docs.iroh.computer/languages/kotlin). Re-bind with the
persisted secret to keep the endpoint id.
