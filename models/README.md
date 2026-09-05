# models/ — sample 3D models (NOT baked into the APK)

These STLs ship to users via the `models-latest` rolling release tarball and
are downloaded on demand by `ModelLibrary` (app-private storage). Keeping them
out of `app/src/main/assets/` saves ~18MB of APK payload.

`catalog.json` is the seed index for the planned P2P model repository
(same schema direction: file/title/description/license/tags). Licenses are
marked `unspecified` until each model's provenance is verified — do not
assert licenses here without checking upstream.
