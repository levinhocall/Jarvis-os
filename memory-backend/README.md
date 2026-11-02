# JARVIS Personal Memory Backend

A self-hosted, authentication-free personal memory service for JARVIS OS. It provides neural semantic embeddings using `sentence-transformers/all-MiniLM-L6-v2` (384 dimensions), cosine retrieval, durable SQLite storage, soft-delete propagation, and a small Mem0-style REST surface.

This is a **Mem0-compatible alternative**, not the official Mem0 Cloud service or a claim of full Mem0 API parity. It avoids external accounts and keeps the memory store under the owner's control.

## Run

Requires Docker with Compose. From this directory:

```bash
docker compose up -d --build
```

Check health:

```bash
curl http://127.0.0.1:8765/health
```

The first start downloads the embedding model and may take several minutes. The SQLite database and model cache persist in named Docker volumes.

## API

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
