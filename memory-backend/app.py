from __future__ import annotations

import json
import os
import sqlite3
import threading
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer

DATA_DIR = Path(os.getenv("JARVIS_MEMORY_DATA", "/data"))
DB_PATH = DATA_DIR / "memories.sqlite3"
MODEL_NAME = os.getenv("JARVIS_EMBEDDING_MODEL", "sentence-transformers/all-MiniLM-L6-v2")
DEFAULT_SCOPE = os.getenv("JARVIS_MEMORY_SCOPE", "jarvis-os-personal")
_model: SentenceTransformer | None = None
_model_lock = threading.Lock()


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def connect() -> sqlite3.Connection:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH, timeout=30)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA foreign_keys=ON")
    return db


def initialize() -> None:
    with connect() as db:
        db.execute("""
            CREATE TABLE IF NOT EXISTS memories (
                id TEXT PRIMARY KEY,
                scope TEXT NOT NULL,
                content TEXT NOT NULL,
                metadata_json TEXT NOT NULL DEFAULT '{}',
                embedding_json TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                deleted_at TEXT
            )
        """)
        db.execute("CREATE INDEX IF NOT EXISTS idx_memories_scope_updated ON memories(scope, updated_at)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_memories_scope_hash ON memories(scope, content_hash)")
        db.execute("""
            CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
                memory_id UNINDEXED, scope UNINDEXED, content,
                tokenize='unicode61'
            )
        """)


def model() -> SentenceTransformer:
    global _model
    if _model is None:
        with _model_lock:
            if _model is None:
                _model = SentenceTransformer(MODEL_NAME, device=os.getenv("JARVIS_EMBEDDING_DEVICE", "cpu"))
    return _model


def embed(text: str) -> list[float]:
    vector = model().encode(text, normalize_embeddings=True, convert_to_numpy=True)
    return vector.astype(np.float32).tolist()


def scope_value(value: str | None) -> str:
    scope = (value or DEFAULT_SCOPE).strip()
    if not scope or len(scope) > 128:
        raise HTTPException(status_code=400, detail="Invalid memory scope")
    return scope


class Message(BaseModel):
    role: str = "user"
    content: str = Field(min_length=1, max_length=20000)


class AddRequest(BaseModel):
    messages: list[Message] = Field(min_length=1, max_length=32)
    user_id: str | None = None
    agent_id: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    memory_id: str | None = None


class SearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=4000)
    user_id: str | None = None
    agent_id: str | None = None
    limit: int = Field(default=10, ge=1, le=100)


class UpdateRequest(BaseModel):
    data: str = Field(min_length=1, max_length=20000)
    user_id: str | None = None
    metadata: dict[str, Any] | None = None


def cosine(a: list[float], b: list[float]) -> float:
    left = np.asarray(a, dtype=np.float32)
    right = np.asarray(b, dtype=np.float32)
    if left.shape != right.shape or left.size == 0:
        return 0.0
    denom = float(np.linalg.norm(left) * np.linalg.norm(right))
    return float(np.dot(left, right) / denom) if denom else 0.0


@asynccontextmanager
async def lifespan(_: FastAPI):
    initialize()
    # Load once during startup so the first user request does not pay model-load latency.
    model()
    yield


app = FastAPI(title="JARVIS Personal Memory", version="1.0.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, Any]:
    return {"status": "ok", "model": MODEL_NAME, "embedding_dimension": model().get_sentence_embedding_dimension()}



class EmbeddingRequest(BaseModel):
    text: str | None = Field(default=None, max_length=4000)
    texts: list[str] = Field(default_factory=list, max_length=64)


@app.post("/v1/embeddings")
def create_embeddings(request: EmbeddingRequest) -> dict[str, Any]:
    texts = [value.strip() for value in request.texts if value.strip()]
    if request.text and request.text.strip():
        texts = [request.text.strip()]
    if not texts:
        raise HTTPException(status_code=400, detail="At least one text is required")
    if any(len(value) > 4000 for value in texts):
        raise HTTPException(status_code=413, detail="Embedding input exceeds 4000 characters")
    vectors = model().encode(texts, normalize_embeddings=True, convert_to_numpy=True)
    return {
        "model": MODEL_NAME,
        "dimension": int(vectors.shape[1]),
        "embeddings": [vector.astype(np.float32).tolist() for vector in vectors],
    }

@app.post("/v1/memories")
def add_memory(request: AddRequest) -> dict[str, Any]:
    scope = scope_value(request.user_id or request.agent_id)
    content = "\n".join(m.content.strip() for m in request.messages if m.content.strip()).strip()
    if not content:
        raise HTTPException(status_code=400, detail="No memory content supplied")
    vector = embed(content)
    content_hash = __import__("hashlib").sha256(content.casefold().encode("utf-8")).hexdigest()
    memory_id = request.memory_id or str(uuid.uuid4())
    timestamp = now_iso()
    metadata = json.dumps(request.metadata, ensure_ascii=False)
    with connect() as db:
        db.execute("""
            INSERT INTO memories(id, scope, content, metadata_json, embedding_json, content_hash, created_at, updated_at, deleted_at)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, NULL)
            ON CONFLICT(id) DO UPDATE SET
                content=excluded.content,
                metadata_json=excluded.metadata_json,
                embedding_json=excluded.embedding_json,
                content_hash=excluded.content_hash,
                updated_at=excluded.updated_at,
                deleted_at=NULL
            WHERE memories.scope=excluded.scope
        """, (memory_id, scope, content, metadata, json.dumps(vector), content_hash, timestamp, timestamp))
        row = db.execute("SELECT scope FROM memories WHERE id=?", (memory_id,)).fetchone()
        if row is None or row["scope"] != scope:
            raise HTTPException(status_code=409, detail="Memory identifier belongs to another scope")
        db.execute("DELETE FROM memories_fts WHERE memory_id=?", (memory_id,))
        db.execute("INSERT INTO memories_fts(memory_id, scope, content) VALUES(?, ?, ?)", (memory_id, scope, content))
    return {"id": memory_id, "memory": content, "event": "ADD", "user_id": scope, "created_at": timestamp}


@app.post("/v1/memories/search")
def search_memories(request: SearchRequest) -> dict[str, Any]:
    scope = scope_value(request.user_id or request.agent_id)
    query_vector = embed(request.query)
    with connect() as db:
        rows = db.execute(
            "SELECT * FROM memories WHERE scope=? AND deleted_at IS NULL",
            (scope,)
        ).fetchall()
    scored = []
    for row in rows:
        try:
            vector = json.loads(row["embedding_json"])
            score = cosine(query_vector, vector)
        except (ValueError, TypeError, json.JSONDecodeError):
            score = 0.0
        if score > 0:
            scored.append({
                "id": row["id"],
                "memory": row["content"],
                "metadata": json.loads(row["metadata_json"]),
                "score": score,
                "created_at": row["created_at"],
                "updated_at": row["updated_at"],
            })
    scored.sort(key=lambda item: item["score"], reverse=True)
    return {"results": scored[:request.limit], "user_id": scope, "model": MODEL_NAME}


@app.get("/v1/memories")
def list_memories(
    user_id: str | None = Query(default=None),
    agent_id: str | None = Query(default=None),
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    updated_after: str | None = Query(default=None),
) -> dict[str, Any]:
    scope = scope_value(user_id or agent_id)
    sql = "SELECT id, content, metadata_json, created_at, updated_at FROM memories WHERE scope=? AND deleted_at IS NULL"
    args: list[Any] = [scope]
    if updated_after:
        sql += " AND updated_at>?"
        args.append(updated_after)
    sql += " ORDER BY updated_at ASC LIMIT ? OFFSET ?"
    args.extend([limit, offset])
    with connect() as db:
        rows = db.execute(sql, args).fetchall()
    return {
        "results": [{
            "id": row["id"],
            "memory": row["content"],
            "metadata": json.loads(row["metadata_json"]),
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
        } for row in rows],
        "user_id": scope,
        "limit": limit,
        "offset": offset,
    }


@app.get("/v1/memories/{memory_id}")
def get_memory(memory_id: str, user_id: str | None = Query(default=None)) -> dict[str, Any]:
    scope = scope_value(user_id)
    with connect() as db:
        row = db.execute(
            "SELECT * FROM memories WHERE id=? AND scope=? AND deleted_at IS NULL",
            (memory_id, scope)
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Memory not found")
    return {
        "id": row["id"],
        "memory": row["content"],
        "metadata": json.loads(row["metadata_json"]),
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


@app.put("/v1/memories/{memory_id}")
def update_memory(memory_id: str, request: UpdateRequest) -> dict[str, Any]:
    scope = scope_value(request.user_id)
    vector = embed(request.data)
    timestamp = now_iso()
    content_hash = __import__("hashlib").sha256(request.data.casefold().encode("utf-8")).hexdigest()
    with connect() as db:
        row = db.execute("SELECT metadata_json FROM memories WHERE id=? AND scope=? AND deleted_at IS NULL", (memory_id, scope)).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="Memory not found")
        metadata = request.metadata if request.metadata is not None else json.loads(row["metadata_json"])
        db.execute("""
            UPDATE memories SET content=?, metadata_json=?, embedding_json=?, content_hash=?, updated_at=?
            WHERE id=? AND scope=?
        """, (request.data, json.dumps(metadata, ensure_ascii=False), json.dumps(vector), content_hash, timestamp, memory_id, scope))
        db.execute("DELETE FROM memories_fts WHERE memory_id=?", (memory_id,))
        db.execute("INSERT INTO memories_fts(memory_id, scope, content) VALUES(?, ?, ?)", (memory_id, scope, request.data))
    return {"id": memory_id, "memory": request.data, "event": "UPDATE", "updated_at": timestamp}


@app.delete("/v1/memories/{memory_id}")
def delete_memory(memory_id: str, user_id: str = Query(...)) -> dict[str, Any]:
    scope = scope_value(user_id)
    timestamp = now_iso()
    with connect() as db:
        cursor = db.execute(
            "UPDATE memories SET deleted_at=?, updated_at=? WHERE id=? AND scope=? AND deleted_at IS NULL",
            (timestamp, timestamp, memory_id, scope)
        )
        db.execute("DELETE FROM memories_fts WHERE memory_id=?", (memory_id,))
    if cursor.rowcount == 0:
        raise HTTPException(status_code=404, detail="Memory not found")
    return {"id": memory_id, "event": "DELETE", "deleted_at": timestamp}


@app.delete("/v1/memories")
def clear_memories(user_id: str = Query(...)) -> dict[str, Any]:
    scope = scope_value(user_id)
    timestamp = now_iso()
    with connect() as db:
        cursor = db.execute(
            "UPDATE memories SET deleted_at=?, updated_at=? WHERE scope=? AND deleted_at IS NULL",
            (timestamp, timestamp, scope)
        )
        db.execute("DELETE FROM memories_fts WHERE scope=?", (scope,))
    return {"deleted": cursor.rowcount, "event": "DELETE_ALL", "user_id": scope, "deleted_at": timestamp}
