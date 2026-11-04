"""Host SQLite check of the actual Room 6->7 SQL; Android SQLCipher still needs device testing."""
import json
import re
import sqlite3
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
schemas = root / 'app/schemas/com.jarvis.assistant.data.memory.MemoryDatabase'
start, end = map(int, sys.argv[1:3]) if len(sys.argv) == 3 else (6, 7)
old = json.loads((schemas / f'{start}.json').read_text())['database']
new = json.loads((schemas / f'{end}.json').read_text())['database']
source = (root / 'app/src/main/java/com/jarvis/assistant/data/memory/MemoryDatabase.kt').read_text()
section = re.split(r'val MIGRATION_|@Volatile', source.split(f'val MIGRATION_{start}_{end} =', 1)[1], maxsplit=1)[0]
statements = re.findall(r'db\.execSQL\("([^"\n]+)"\)', section)
assert len(statements) == (5 if start == 6 else 2)

def create(db, schema):
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))

def shape(db, table):
    columns = db.execute(f'PRAGMA table_info("{table}")').fetchall()
    indices = sorted((row[1], row[2], db.execute(f'PRAGMA index_info("{row[1]}")').fetchall())
                     for row in db.execute(f'PRAGMA index_list("{table}")') if row[3] != 'pk')
    return columns, indices

actual = sqlite3.connect(':memory:')
expected = sqlite3.connect(':memory:')
create(actual, old)
create(expected, new)
# Seed every old table, including memories, missions and sync cursors, then compare exact rows.
before = {}
for entity in old['entities']:
    name = entity['tableName']
    columns = actual.execute(f'PRAGMA table_info("{name}")').fetchall()
    values = [42 if 'INT' in c[2] else 0.5 if 'REAL' in c[2] else b'preserve' if 'BLOB' in c[2] else 'preserve-local-data' for c in columns]
    actual.execute(f'INSERT INTO "{name}" VALUES ({",".join("?" for _ in values)})', values)
    before[name] = actual.execute(f'SELECT * FROM "{name}"').fetchall()
for sql in statements:
    actual.execute(sql)
assert len(new['entities']) == len(old['entities']) + (3 if start == 6 else 1)
for entity in new['entities']:
    name = entity['tableName']
    assert shape(actual, name) == shape(expected, name), name
for name, rows in before.items():
    assert actual.execute(f'SELECT * FROM "{name}"').fetchall() == rows, name
print(f'PASS: {start}->{end} schema matches Room export; all {len(before)} existing tables retain seeded rows.')
