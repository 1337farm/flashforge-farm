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
