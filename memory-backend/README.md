# JARVIS Personal Memory Backend

A self-hosted, authentication-free personal memory service for JARVIS OS. It provides neural semantic embeddings using `sentence-transformers/all-MiniLM-L6-v2` (384 dimensions), cosine retrieval, durable SQLite storage, soft-delete propagation, and a small Mem0-style REST surface.

This is a **Mem0-compatible alternative**, not the official Mem0 Cloud service or a claim of full Mem0 API parity. It avoids external accounts and keeps the memory store under the owner's control.

## Run

Requires Docker with Compose. From this directory:

```bash
docker compose up -d --build
```

Check health and container state:

```bash
curl http://127.0.0.1:8765/health
docker compose ps
```

Compose now checks the `/health` endpoint every 30 seconds with a 20-minute startup grace period for the first neural-model download. The container restarts if its process exits; a health check reports readiness but does not itself deploy the service to a public host.

The first start downloads the embedding model and may take several minutes. The SQLite database and model cache persist in named Docker volumes.

## Always-on VPS deployment

The repository now includes a systemd boot unit and a periodic unhealthy-container recovery timer. These files prepare the service for a Linux VPS; they do **not** provision a VPS or make it live until installed on a host.

On an Ubuntu/Debian VPS with Docker Engine and the Compose plugin installed:

```bash
sudo mkdir -p /opt/jarvis-memory
sudo cp -a memory-backend /opt/jarvis-memory/
sudo cp /opt/jarvis-memory/memory-backend/deploy/jarvis-memory.service /etc/systemd/system/
sudo cp /opt/jarvis-memory/memory-backend/deploy/jarvis-memory-health.service /etc/systemd/system/
sudo cp /opt/jarvis-memory/memory-backend/deploy/jarvis-memory-health.timer /etc/systemd/system/
sudo systemctl enable --now docker
sudo systemctl daemon-reload
sudo systemctl enable --now jarvis-memory.service
sudo systemctl enable --now jarvis-memory-health.timer
sudo systemctl status jarvis-memory.service
sudo systemctl status jarvis-memory-health.timer
docker compose -f /opt/jarvis-memory/memory-backend/docker-compose.yml ps
```

The container uses `restart: unless-stopped`, persistent Docker volumes, a startup grace period for the first embedding-model download, and a systemd timer that restarts the container if Docker reports it unhealthy. Backups of the named `jarvis-memory-data` volume should be scheduled separately.

The API binds to loopback only. For phone access, put a TLS reverse proxy on a private VPN/network in front of `127.0.0.1:8765`; do not expose port 8765 directly to the public internet. Set `JARVIS_MEMORY_SYNC_ENDPOINT` to that private HTTPS endpoint. The app has no login/account system; keep access limited at the network boundary.

A host, DNS/VPN route, TLS endpoint and actual installation are still required. No VPS has been provisioned or deployed from this repository session.

## Android integration — currently disabled

The current Android application keeps memories in its local Room/SQLCipher database. `MEMORY_SYNC_ENDPOINT` is intentionally blank, startup cancels legacy upload jobs, and `MemorySyncWorker` performs no network work. Setting `JARVIS_MEMORY_SYNC_ENDPOINT` does **not** enable sync in this revision. Legacy remote/Gemini embedding adapters also return explicitly named local deterministic vectors without reading credentials or contacting providers.

The Room schema retains stable sync IDs, acknowledgement fields and a cursor table from earlier work. These are not evidence of a working sync client. Optional bidirectional Android synchronization, consent controls, conflict review, deletion acknowledgement and integration/device tests remain unfinished. Remote data must never silently overwrite or erase local memories. Neural embeddings are implemented only by this standalone backend; Android hashing is not neural inference.

## Backend tests

Run storage/protocol tests from this directory with FastAPI, Pydantic and NumPy installed. These tests isolate neural inference and do not download or validate a model. The full `requirements.txt` remains necessary for production startup:

```bash
python -m unittest discover -s tests -v
```

## API

For sync clients, request `GET /v1/memories?user_id=jarvis-os-personal&limit=200&include_deleted=true`. Subsequent pages use both `updated_after` and `after_id` from `next_cursor`. The compound cursor prevents records sharing the same timestamp from being skipped. The endpoint includes `deleted_at` for tombstones when `include_deleted=true`.

- `POST /v1/embeddings` — batch neural embeddings
- `POST /v1/memories` — add/update a memory
- `POST /v1/memories/search` — neural semantic search
- `GET /v1/memories` — paginated list; supports `updated_after`, `after_id`, and `include_deleted=true` for tombstone-aware keyset sync
- `GET /v1/memories/{id}` — fetch
- `PUT /v1/memories/{id}` — update
- `DELETE /v1/memories/{id}` — soft delete
- `DELETE /v1/memories` — soft delete all in a scope
- `GET /health` — health and embedding model information

Example:

```bash
curl -X POST http://127.0.0.1:8765/v1/memories \
  -H 'Content-Type: application/json' \
  -d '{"user_id":"jarvis-os-personal","messages":[{"role":"user","content":"I prefer concise Hindi and English answers."}],"metadata":{"source":"jarvis-os"}}'

curl -X POST http://127.0.0.1:8765/v1/memories/search \
  -H 'Content-Type: application/json' \
  -d '{"user_id":"jarvis-os-personal","query":"What language style do I prefer?","limit":5}'
```

## Conditional writes and cursor safety

`POST /v1/memories` and `PUT /v1/memories/{id}` accept `expected_updated_at`. DELETE accepts the same optional query parameter. Set it to the exact version last read; a mismatch returns HTTP 409 without modifying the record or its search index. For POST, an empty string means create only. Keep the local version on conflict and ask the owner to reconcile; do not retry unconditionally. Legacy callers that omit the field retain unconditional behavior and do **not** gain conflict protection.

POST returns both the original `created_at` and current `updated_at`. Writes allocate monotonically increasing UTC timestamps under a SQLite write lock, including after clock rollback, so a saved keyset cursor does not skip a later write. Tombstones remain available through `include_deleted=true`. Restore of an older server database invalidates this guarantee and requires explicit full reconciliation; no Android restore protocol is claimed.

## No login or authentication

There is no login, signup, account, subscription, API-key, or authentication layer. The `user_id` field is only a data-partition label for Mem0-style API compatibility; it does not authenticate a caller.

**Keep this service private.** Compose binds to `127.0.0.1` by default. Do not change that to a public interface or expose port 8765 directly to the internet. For remote phone-to-server sync, use a private VPN/LAN or a trusted private reverse-proxy network with TLS and network-level access controls. These controls protect the personal memory store without adding an app login system.

## Data and limitations

- Memory content and metadata are stored in SQLite; vectors are stored as normalized JSON arrays.
- Deletes are soft deletes so a future sync client can propagate deletion instead of resurrecting old memories.
- Semantic search uses a real pretrained neural embedding model; it is not a hand-authored synonym table or hash-vector approximation.
- This initial backend is a standalone service. JARVIS Android sync-client wiring, conflict resolution policy, and end-to-end phone/server sync validation are separate integration steps and must not be marked complete until exercised against a running instance.
