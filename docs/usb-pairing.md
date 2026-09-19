# USB reverse-pairing: printer dials the phone first

Status: functional end-to-end (2026-09-11).
Phone accept-loop + dial (`pairAccept`/`pairStop`/`pairDial`) live in the
`irohbridge` AAR >= 0.4.0 (sibling `iroh-android-native`, `native_iroh_engine`).
Printer dialer is `farm-agent` (sibling `farm-agent/` crate, `agent-v0.1.0`
release assets); the shell harness invokes it when present on the stick.
Farm app consumes both via `PairingRuntime` + `UsbProvisioningManager`.

## Problem

Phone and printer are usually on different networks (mobile data vs home Wi-Fi), both
behind NAT. iroh solves reachability via relay-assisted holepunching, but three things
were missing: the phone ran no node, the printer's NodeId was unknown to the phone,
and the printer firewall blocked the relay handshake (all TCP dropped).

Manual pairing (QR / typed codes) is rejected: the USB provisioning walk already is a
trusted physical channel, so it carries the pairing credentials instead.

## Design

Reverse the first dial: the **printer calls the phone**, not the other way around.

1. Provisioning writes `phone-<printer-id>-<phonesuffix>.addr` flat files to the
   USB stick root (flat, not a subdirectory, because SAF writes files, not
   trees): the phone's NodeId, relay list, ALPN, and a one-time pairing token.
   One file per provisioning phone, so a second phone adds a second file.
2. On boot the printer stages every `phone-*.addr` next to the deployment and
   dials each phone node over iroh, presenting the token on ALPN `farm/pair/0`,
   retrying with backoff (paired files are renamed aside).
3. The phone's accept-loop checks the token against a pending, expiring pairing
   record. On match it **binds the authenticated peer NodeId to that printer** in
   the fleet and burns the token.
4. Later phone→printer dials use the stored NodeId. No QR, no typing, no server.

Why printer-first and not phone-first:

- The printer's NodeId is unknowable in advance (its key file was never a valid
  node secret; its identity is effectively random per install). The phone's NodeId
  is stable and known at provision time.
- NodeIds are public addresses, not secrets: anyone can dial the phone. Gating the
  bind on a token delivered over the physical USB channel is what makes the first
  contact trustworthy.
- The printer (stable home Wi-Fi) initiates toward the phone instead of the phone
  reaching into unknown networks first.

## Payload schema: `phone-<printer-id>-<phonesuffix>.addr`

JSON, UTF-8, written by `UsbProvisioningManager.writePhoneAddr`:

```json
{
  "format": "flashforge-farm-phone-addr",
  "version": 1,
  "nodeId": "<64 hex chars: phone Ed25519 public key = iroh NodeId>",
  "relays": [],
  "alpn": "farm/pair/0",
  "token": "<64 hex chars: 256-bit one-time pairing secret>",
  "printerId": "<fleet printer id this file pairs>",
  "issuedAt": 1726000000000
}
```

- `relays: []` means "use your compiled-in defaults" (n0 public relays on both
  ends). Pin explicit URLs here only to override (e.g. a self-hosted relay later).
- Filename is sanitized to `[A-Za-z0-9_-]`; the phonesuffix is the first 8 hex
  chars of the provisioning phone's NodeId, so files from different phones never
  collide and the printer dials each.

## Pairing protocol: ALPN `farm/pair/0`

Printer (`farm-agent`, see below) opens a bi-directional stream to the phone's
NodeAddr and sends one JSON line, then half-closes:

```json
{"token": "<64 hex>", "printer": "<printerId>", "agent": "farm-agent/0.1"}
```

Phone responds one JSON line and closes:

```json
{"ok": true}   // token accepted, NodeId bound
{"ok": false}  // unknown/expired/burned token — printer backs off, operator re-provisions
```

The phone learns the printer's NodeId from the authenticated QUIC handshake itself
(peer public key == NodeId, verified by TLS), never from the message body.

## Token lifecycle (`Printer.pairToken` / `pairExpiresAt`)

- Created at provision time: 256-bit `SecureRandom`, hex-encoded.
- Stored on the fleet `Printer` record (`pairToken`, `pairExpiresAt = now + 48h`).
- `PairingService.onPairBeacon(peerNodeIdHex, tokenHex)`:
  find printer with equal token and `now < pairExpiresAt` → set `nodeId`,
  clear token fields, `savePrinters`, return true. Otherwise false.
- Binding is only attempted while a record is pending; after burn/expiry the
  phone ignores pair-beacons. Re-provisioning issues a fresh token.
- Phone node secret (`node_seed`, 32 bytes, app-private file) is the long-lived
  identity; the NodeId is standard Ed25519 pubkey derivation, so Java and the
  Rust engine agree byte-for-byte from the same seed.

## Firewall

`flashforge_init.sh` now allows outbound **TCP 443** (after the LAN + UDP
allowances, before the blanket TCP DROP). The iroh relay handshake is
HTTPS-over-TCP; without this, no relay registration, no introduction, no
holepunch coordination, no relayed fallback. Plain HTTP (80), cloud endpoints,
and all other TCP stay blocked; OTA stays dead as before.

## Fleet record changes

`Printer` gains: `nodeId` (bound peer NodeId, null until paired), `model`,
`firmwareVersion`, `pairToken`, `pairExpiresAt`. All nullable → old records and
old backups parse unchanged (Gson ignores missing keys).

## Build / publish / run

- Engine AAR: sibling `android.yml` builds + publishes to GitHub Packages on
  `engine-v*` tags. `pairAccept`/`pairStop`/`pairDial` ship in **0.4.0**.
  Farm pins `com.example.irohapp:irohbridge:0.4.0` (`app/build.gradle`).
- Printer agent: sibling `agent.yml` builds ARMv7 + x86_64 on `agent-v*` tags
  and attaches `farm-agent-<target>` binaries to the tag release. The farm
  app downloads the ARMv7 asset (`UsbProvisioningManager.FARM_AGENT_DEFAULT_URL`)
  onto the stick; `flashforge_init.sh` installs + chmods it.
- Phone runtime: `PairingTask` (boot) inits the engine with `NodeIdentity`'s
  seed and starts the accept-loop with all pending tokens; provisioning and
  backup-restore refresh the token set; a bound beacon posts a snackbar and
  the fleet card flips to "Paired — P2P ready".
- Inbound pairing needs the app alive (Android Doze kills background relay
  connections; no foreground service yet) — the printer retry loop
  (30s × 1 day, then hourly, reboot-persistent via `pairing.sh`) covers the gap.

## Testing the tooling (what runs where)

The P2P path is covered in three tiers; the gaps are called out, not hidden.

1. **JVM unit tests (run here, no device):**
   `:app:testDebugUnitTest` — currently 124 tests, including the P2P pair:
   - `NodeIdentityTest` (6): toHex vectors, fixed-seed vector pinned against
     `native_iroh_engine/tests/nodeid_vector.rs` (Rust iroh 1.1.0 agrees
     byte-for-byte with Java net.i2p eddsa), lowercase-hex-64 shape, seed
     persistence + short-file repair.
   - `PairingServiceTest` (7): token format, case-insensitive match, expiry
     boundary (`pairExpiresAt > now`), bind-and-clear, null safety,
     multi-printer scan.
   - `ConfigSerializeFlowTest` (existing): bool→enum migration guard that
     feeds the native slicer.

   Run: `./gradlew :app:testDebugUnitTest` (needs `GH_PACKAGES_USER/TOKEN`
   for the `irohbridge:0.4.0` resolve).

2. **Engine (Rust, runs in CI + locally):**
   `native_iroh_engine` — `cargo test --locked` covers
   `tests/nodeid_vector.rs`; `cargo check` on all four Android ABIs
   (arm64, armv7, i686, x86_64) is wired as the `engine` job in
   `iroh-android-native/.github/workflows/android.yml` and is a required
   merge check alongside `test`/`apk`.

3. **Source-text guards (seconds, run here):**
   `scripts/tests/test_ci_guards.py` (workflow ↔ branch-protection mapping)
   and `test_jni_contract.py` (119/119 Java↔C++ JNI signatures) — both green.
   Headless JVM E2E (`run_e2e_tests.sh`, 93 tests) covers UI/slice flows via
   JNI shadow mocks, not the P2P path.

4. **Device (not runnable in this environment — no adb device attached):**
   needs a physical phone + provisioned printer on real networks:
   - `CrossAppTicketInteropTest` (instrumented, in `iroh-android-native`):
     publish → ticket → fetch roundtrip, sync announce/merge, ticket-format
     validation. Run via Android Studio / `./gradlew connectedAndroidTest`.
   - Live reverse-pairing pass: provision USB stick (writes `phone-*.addr`),
     boot printer, confirm accept-loop binds NodeId, fleet card flips to
     "Paired — P2P ready", then a phone→printer `pairDial`.
   - Doze check: app backgrounded 30+ min, printer dial still binds (see
     hardening item 1 below).

## Remaining hardening (not built)

1. **Foreground service** for the pairing window so inbound dials survive Doze
   with the app backgrounded.
2. **Phone→printer dial UX**: `pairDial` exists in the AAR; no fleet UI uses it
   yet (tunnel/telemetry comes next).
3. **Token-expiry UX**: expired tokens just never bind; surface "re-provision"
   explicitly in the fleet card.

## Security notes

- `nodeId` is public; `node_seed` and unburned `pairToken`s never leave the
  device except the token's one trip over USB + its presentation on an
  E2E-encrypted stream.
- A malicious stick could plant a stranger's phone.addr — but provisioning is an
  explicit user action with physical access; the threat model starts there.
- Relay operator sees NodeIds + timing, never payloads (QUIC/TLS node-to-node).
