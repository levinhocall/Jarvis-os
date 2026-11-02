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

Compose now checks the `/health` endpoint every 30 seconds with a 10-minute startup grace period for the first neural-model download. The container restarts if its process exits; a health check reports readiness but does not itself deploy the service to a public host.

The first start downloads the embedding model and may take several minutes. The SQLite database and model cache persist in named Docker volumes.

## Android integration

Set `JARVIS_MEMORY_SYNC_ENDPOINT` in `Part 7/gradle.properties` or the Gradle environment to this service's HTTPS URL (or a trusted private-LAN URL). Example:

```properties
JARVIS_MEMORY_SYNC_ENDPOINT=https://your-private-memory-host
```

When this value is blank, Android keeps local embeddings and does not schedule cloud sync. When configured, Android uses the neural embedding endpoint for new/search vectors, gradually re-indexes up to 24 older memories per semantic-search request, and queues local-to-server memory sync every six hours while connected. The Android sync worker is currently **local-authoritative push sync**: local updates are uploaded idempotently and local deletions are propagated, but remote edits are not pulled back into Room yet. Do not treat it as full multi-device bidirectional sync.

## API

- `POST /v1/embeddings` — batch neural embeddings
- `POST /v1/memories` — add/update a memory
- `POST /v1/memories/search` — neural semantic search
- `GET /v1/memories` — paginated list; supports `updated_after`
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

## No login or authentication

There is no login, signup, account, subscription, API-key, or authentication layer. The `user_id` field is only a data-partition label for Mem0-style API compatibility; it does not authenticate a caller.

**Keep this service private.** Compose binds to `127.0.0.1` by default. Do not change that to a public interface or expose port 8765 directly to the internet. For remote phone-to-server sync, use a private VPN/LAN or a trusted private reverse-proxy network with TLS and network-level access controls. These controls protect the personal memory store without adding an app login system.

## Data and limitations

- Memory content and metadata are stored in SQLite; vectors are stored as normalized JSON arrays.
- Deletes are soft deletes so a future sync client can propagate deletion instead of resurrecting old memories.
- Semantic search uses a real pretrained neural embedding model; it is not a hand-authored synonym table or hash-vector approximation.
- This initial backend is a standalone service. JARVIS Android sync-client wiring, conflict resolution policy, and end-to-end phone/server sync validation are separate integration steps and must not be marked complete until exercised against a running instance.
