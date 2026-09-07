# Distributed Search Index (no hub)

## Model
Every model is a metadata blob + file blobs in iroh-blobs. Identity =
blake3 hash of the metadata blob. Sharing = `BlobTicket` strings
(hash + provider address). Tickets are the only thing users exchange.

## Metadata blob (stored JSON)
`ModelMetadata` fields plus publisher-added arrays (same order as `files`):
- `hashes`: blake3 hex per file
- `tickets`: per-file `BlobTicket` strings (provider = publisher)
- `sizes`: byte size per file

## Announcements (epidemic sync)
`sync_announce()` stores `{"v":1,"models":[{"h":hash,"t":ticket}, ...]}` (cap
200) and returns its ticket. `sync_merge(ticket)` fetches the announcement,
then each unknown metadata blob (cap 100), and indexes them. Returns
`{"new_models":n,"model_tickets":[...]}`. Peers exchange announcements
out-of-band (chat, QR, forum post); every merge spreads knowledge further.
No server, no DHT, no gossip overlay — propagation is epidemic over tickets.

## Local index (per node, in Rust)
`keyword -> set<model_hash>`, `model_hash -> metadata_json`. Keywords from
title (full + words), description words, category, tags, designer name.
`search_query` is local-only; run `sync_merge` first to learn peers' models.

## Flows
Publish: `model_publish(json, file_bytes)` → metadata ticket (share it).
Fetch: `fetch_start(ticket, dir)` → poll `fetch_poll(handle)` →
`metadata`/`progress`/`done`/`error` JSON → `fetch_stop(handle)`.
Search: `search_query` → `search_get_metadata(hash)` → `ticket_for(hash)` or
reuse the ticket from the announcement → `fetch_start`.

## Trust (Java side, unchanged)
`ModelVerifier` gates every fetch completion; `TrustStore` TOFU +
`LabelAggregator` moderator feeds gate publishers; `UserProfile` display
always pairs names with key hashes.
