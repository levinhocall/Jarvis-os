import json
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
from concurrent.futures import ThreadPoolExecutor
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

    def test_wrong_scope_delete_preserves_owner_and_search_index(self):
        with memory_app.connect() as db:
            db.execute("INSERT INTO memories_fts(memory_id, scope, content) VALUES (?, ?, ?)",
                       ("a", "test-scope", "owner fact"))
        with self.assertRaises(memory_app.HTTPException) as error:
            memory_app.delete_memory("a", user_id="other-scope")
        self.assertEqual(404, error.exception.status_code)
        with memory_app.connect() as db:
            row = db.execute("SELECT deleted_at FROM memories WHERE id='a'").fetchone()
            indexed = db.execute("SELECT content FROM memories_fts WHERE memory_id='a'").fetchone()
        self.assertIsNone(row["deleted_at"])
        self.assertEqual("owner fact", indexed["content"])

    def test_delete_propagates_tombstone_and_removes_own_index(self):
        with memory_app.connect() as db:
            db.execute("INSERT INTO memories_fts(memory_id, scope, content) VALUES (?, ?, ?)",
                       ("a", "test-scope", "owner fact"))
        result = memory_app.delete_memory("a", user_id="test-scope")
        self.assertEqual("DELETE", result["event"])
        with memory_app.connect() as db:
            row = db.execute("SELECT updated_at, deleted_at FROM memories WHERE id='a'").fetchone()
            count = db.execute("SELECT COUNT(*) FROM memories_fts WHERE memory_id='a'").fetchone()[0]
        self.assertEqual(result["deleted_at"], row["deleted_at"])
        self.assertEqual(row["updated_at"], row["deleted_at"])
        self.assertEqual(0, count)

    def test_clock_rollback_write_stays_after_saved_cursor(self):
        with patch.object(memory_app, "now_iso", return_value="2020-01-01T00:00:00+00:00"):
            result = memory_app.delete_memory("a", user_id="test-scope")
        page = memory_app.list_memories(
            user_id="test-scope", agent_id=None, limit=10, offset=0,
            updated_after="2026-01-01T00:00:00+00:00", after_id="c", include_deleted=True
        )
        self.assertEqual(["a"], [row["id"] for row in page["results"]])
        self.assertEqual(result["deleted_at"], page["next_cursor"]["updated_at"])

    def test_compare_and_swap_rejects_stale_delete_without_data_loss(self):
        with self.assertRaises(memory_app.HTTPException) as error:
            memory_app.delete_memory("a", user_id="test-scope", expected_updated_at="stale")
        self.assertEqual(409, error.exception.status_code)
        self.assertEqual("memory a", memory_app.get_memory("a", user_id="test-scope")["memory"])

    def test_concurrent_conditional_updates_have_exactly_one_winner(self):
        def update(content):
            try:
                memory_app.update_memory("a", memory_app.UpdateRequest(
                    data=content, user_id="test-scope", expected_updated_at="2026-01-01T00:00:00+00:00"))
                return 200
            except memory_app.HTTPException as error:
                return error.status_code
        # Neural inference is intentionally outside these storage/protocol tests.
        with patch.object(memory_app, "embed", return_value=[1.0, 0.0]):
            with ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(update, ["first", "second"]))
        self.assertEqual([200, 409], sorted(results))

    def test_conditional_add_cannot_resurrect_a_newer_tombstone(self):
        memory_app.delete_memory("a", user_id="test-scope")
        with patch.object(memory_app, "embed", return_value=[1.0, 0.0]):
            with self.assertRaises(memory_app.HTTPException) as error:
                memory_app.add_memory(memory_app.AddRequest(
                    messages=[memory_app.Message(content="stale fact")], user_id="test-scope",
                    memory_id="a", expected_updated_at="2026-01-01T00:00:00+00:00"))
        self.assertEqual(409, error.exception.status_code)
        with memory_app.connect() as db:
            self.assertIsNotNone(db.execute("SELECT deleted_at FROM memories WHERE id='a'").fetchone()[0])

    def test_create_only_add_and_update_preserve_original_creation_time(self):
        with patch.object(memory_app, "embed", return_value=[1.0, 0.0]):
            created = memory_app.add_memory(memory_app.AddRequest(
                messages=[memory_app.Message(content="new fact")], user_id="test-scope",
                memory_id="new", expected_updated_at=""))
            updated = memory_app.add_memory(memory_app.AddRequest(
                messages=[memory_app.Message(content="corrected fact")], user_id="test-scope",
                memory_id="new", expected_updated_at=created["updated_at"]))
        self.assertEqual(created["created_at"], updated["created_at"])
        self.assertGreater(updated["updated_at"], created["updated_at"])

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
