# models/ — sample 3D models (NOT baked into the APK)

These STLs ship to users via the `models-latest` rolling release tarball and
are downloaded on demand by `ModelLibrary` (app-private storage). Keeping them
out of `app/src/main/assets/` saves ~18MB of APK payload.

`catalog.json` is the seed index for the planned P2P model repository
(same schema direction: file/title/description/license/tags). Licenses are
marked `unspecified` until each model's provenance is verified — do not
assert licenses here without checking upstream.

## Publishing

The `publish-models` CI job (renamed `models` in the native workflow) builds
`models.zip` deterministically and publishes the `models-latest` release on
every push to `main`. The archive and every per-file sha256 are pinned in
`models-manifest.json`, which the app verifies against `ModelLibrary.MANIFEST_SHA`.

To add/change a model:

1. Drop the STL into `models/`.
2. Run `scripts/publish-models.sh --refresh` — it rebuilds `models.zip`,
   regenerates the manifest, and prints the new `MANIFEST_SHA`.
3. Copy that value into `ModelLibrary.MANIFEST_SHA` and commit.
4. CI re-derives the same sha (deterministic build) and republishes on merge.
   A mismatch fails the job, so the pin can never drift silently.
