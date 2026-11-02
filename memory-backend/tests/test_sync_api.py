import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

import app as memory_app


class MemorySyncApiTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.old_data_dir = memory_app.DATA_DIR
        self.old_db_path = memory_app.DB_PATH
        memory_app.DATA_DIR = Path(self.temp_dir.name)
        memory_app.DB_PATH = memory_app.DATA_DIR / "test.sqlite3"
        memory_app.initialize()
        with memory_app.connect() as db:
            for memory_id, deleted_at in (("a", None), ("b", "2026-01-01T00:00:00+00:00"), ("c", None)):
                db.execute(
                    """INSERT INTO memories
                    (id, scope, content, metadata_json, embedding_json, content_hash, created_at, updated_at, deleted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    (memory_id, "test-scope", "memory " + memory_id, json.dumps({}), "[]", memory_id,
                     "2026-01-01T00:00:00+00:00", "2026-01-01T00:00:00+00:00", deleted_at),
                )

    def tearDown(self):
        memory_app.DATA_DIR = self.old_data_dir
        memory_app.DB_PATH = self.old_db_path
        self.temp_dir.cleanup()

    def test_keyset_cursor_keeps_same_timestamp_rows(self):
        first = memory_app.list_memories(
            user_id="test-scope", agent_id=None, limit=1, offset=0,
            updated_after=None, after_id=None, include_deleted=True
        )
        cursor = first["next_cursor"]
        second = memory_app.list_memories(
            user_id="test-scope", agent_id=None, limit=2, offset=0,
            updated_after=cursor["updated_at"], after_id=cursor["id"], include_deleted=True
        )
        ids = [first["results"][0]["id"]] + [row["id"] for row in second["results"]]
        self.assertEqual(["a", "b", "c"], ids)

    def test_sync_listing_includes_tombstones_only_when_requested(self):
        active = memory_app.list_memories(
            user_id="test-scope", agent_id=None, limit=10, offset=0,
            updated_after=None, after_id=None, include_deleted=False
        )
        all_rows = memory_app.list_memories(
            user_id="test-scope", agent_id=None, limit=10, offset=0,
            updated_after=None, after_id=None, include_deleted=True
        )
        self.assertEqual({"a", "c"}, {row["id"] for row in active["results"]})
        deleted = next(row for row in all_rows["results"] if row["id"] == "b")
        self.assertIsNotNone(deleted["deleted_at"])


if __name__ == "__main__":
    unittest.main()
