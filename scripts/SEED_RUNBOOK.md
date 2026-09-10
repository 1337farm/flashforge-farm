# Seeding run runbook (issue #46)

## 1. Generate the admin key (operator machine)
`python3 scripts/gen_admin_key.py` — pure stdlib, fails closed on a
base-point order self-test before emitting anything.
- `ADMIN_SEED_HEX`: operator secret. Never commit, never bake into the app.
  Load it on the admin device into SecurePrefs (EncryptedSharedPreferences).
- `ADMIN_PUBKEY_HEX`: bake into `ModelRepoFragment.DEFAULT_MODERATORS`
  (currently empty) and open a PR. This makes the moderator-feed path
  (`LabelAggregator`) live.

Cross-check the keypair live: announce a profile with the seed
(`SearchClient.announceWithProfile`) and confirm peers report the name
as `verified` (`SearchClient.profileLabel`).

## 2. Stage the models (admin device)
`ModelLibrary` fetches per-model from the `models-latest` release with
manifest + zip SHA verification. The migration run needs the 18 `.stl`
files under `filesDir/models/` (see `models/catalog.json`).

## 3. Publishing run (admin device)
Open the model-repo screen: `triggerMigration` → `P2pManager.migrateIfSeeded`
(one-shot via `iroh_migrated_v1` pref) publishes each seed model through
`ModelMigrator.migrateAll` with signed metadata (`SeedModels` system key).
To re-run: clear the `iroh_migrated_v1` pref.

## 4. Post the seed
Tap Share (or read the per-model ticket from migration status): the
`sync_announce` ticket from `announceWithProfile` is the network seed —
post it where joiners can find it (issue #46). Peers merge it via
`mergeAnnouncement` and resolve the admin profile ticket with signature
verification.
