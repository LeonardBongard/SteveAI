// Memory persistence: a single SQLite file with the sqlite-vec extension
// loaded for vector similarity. One DB, one file, one process — see
// docs/COMPANION_V1_DIRECTION.md §3.3.
//
// Tables (created on first open):
// - conversation: sliding-window source-of-truth + retrieval index
// - pinned_facts: durable preferences/landmarks the player has stated
// - episodic: (what, where, when) place events — MrSteve-style PEM
// - playbook: hindsight-captured natural-language recipes
//
// Embeddings are stored as BLOBs of float32 in big-endian-agnostic native
// order. The fixed dimension (EMBED_DIM) matches nomic-embed-text. If you
// switch the embed model, bump SCHEMA_VERSION and add a migration.

import Database, { type Database as DB } from 'better-sqlite3';
import * as sqliteVec from 'sqlite-vec';
import path from 'node:path';
import fs from 'node:fs';
import { logs } from '../log.js';

const DEFAULT_PATH = path.resolve(process.cwd(), 'data', 'memory.db');
export const EMBED_DIM = 768; // nomic-embed-text
const SCHEMA_VERSION = 5;

let db: DB | null = null;

export function openMemoryStore(file: string = DEFAULT_PATH): DB {
  if (db) return db;

  fs.mkdirSync(path.dirname(file), { recursive: true });

  const handle = new Database(file);
  handle.pragma('journal_mode = WAL');
  handle.pragma('foreign_keys = ON');

  // Load sqlite-vec extension for vec_distance_* SQL functions.
  sqliteVec.load(handle);

  initSchema(handle);

  db = handle;
  logs.mem.info({ file, embedDim: EMBED_DIM }, 'memory store opened');
  return db;
}

export function closeMemoryStore(): void {
  if (db) {
    db.close();
    db = null;
  }
}

function initSchema(handle: DB): void {
  handle.exec(`
    CREATE TABLE IF NOT EXISTS meta (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS conversation (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      ts TEXT NOT NULL,
      speaker TEXT NOT NULL,
      message TEXT NOT NULL,
      embedding BLOB
    );
    CREATE INDEX IF NOT EXISTS idx_conversation_ts ON conversation(ts);

    CREATE TABLE IF NOT EXISTS pinned_facts (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL,
      context TEXT,
      ts TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS episodic (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      ts TEXT NOT NULL,
      event TEXT NOT NULL,
      x INTEGER NOT NULL,
      y INTEGER NOT NULL,
      z INTEGER NOT NULL,
      dimension TEXT NOT NULL,
      context TEXT
    );
    CREATE INDEX IF NOT EXISTS idx_episodic_event ON episodic(event);
    CREATE INDEX IF NOT EXISTS idx_episodic_xyz ON episodic(x, y, z);

    CREATE TABLE IF NOT EXISTS playbook (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      ts TEXT NOT NULL,
      name TEXT NOT NULL UNIQUE,
      description TEXT NOT NULL,
      steps TEXT NOT NULL,
      success_count INTEGER NOT NULL DEFAULT 1,
      embedding BLOB
    );

    -- v2 skill library: stores executable JS code that the LLM has written
    -- and verified works. Replaces the v1 playbook (which only stored NL
    -- step descriptions). See docs/COMPANION_V2_DIRECTION.md §3.3.
    -- v3 (robustness P5): adds verified column. Save logic refuses to
    -- overwrite verified=1 skills (forces the LLM to pick a new name);
    -- unverified skills can still be patched in-place during DEPS retry.
    -- v4 (auto-demote): adds consecutive_failures column. Verified skills
    -- that fail N times in a row get auto-demoted (verified=0) so the
    -- library self-heals when external assumptions change (e.g. RAG data
    -- updates that make old skills incompatible).
    CREATE TABLE IF NOT EXISTS skills (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      ts TEXT NOT NULL,
      name TEXT NOT NULL UNIQUE,
      description TEXT NOT NULL,
      code TEXT NOT NULL,
      success_count INTEGER NOT NULL DEFAULT 0,
      failure_count INTEGER NOT NULL DEFAULT 0,
      last_invoked_at TEXT,
      embedding BLOB,
      verified INTEGER NOT NULL DEFAULT 0,
      consecutive_failures INTEGER NOT NULL DEFAULT 0,
      -- v5 (T1.2): JSON arrays. prerequisites = skill names this skill invokes
      -- and that should run first. produces_items = item names this skill is
      -- expected to add to inventory (best-effort declaration from the LLM).
      prerequisites TEXT NOT NULL DEFAULT '[]',
      produces_items TEXT NOT NULL DEFAULT '[]'
    );

    -- v5 (T1.1): multi-turn goal tracking. Goals persist across turns/sessions
    -- with a status. Active goals are auto-injected into the bot-state snapshot
    -- so the LLM doesn't lose track of what the player asked for.
    CREATE TABLE IF NOT EXISTS goals (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      ts TEXT NOT NULL,
      text TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'active',
      -- 'active' | 'done' | 'cancelled'
      parent_id INTEGER,
      ts_done TEXT,
      FOREIGN KEY (parent_id) REFERENCES goals(id)
    );
    CREATE INDEX IF NOT EXISTS idx_goals_status ON goals(status);

    -- v5 (T2.1): experience pool. Snapshot of world state + outcome each
    -- time a skill is invoked. Lets the LLM see "this skill worked when I
    -- had X in inventory at Y" instead of just "this skill exists."
    CREATE TABLE IF NOT EXISTS skill_trials (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      ts TEXT NOT NULL,
      skill_name TEXT NOT NULL,
      ok INTEGER NOT NULL,
      duration_ms INTEGER,
      state_before TEXT NOT NULL,  -- JSON: { position, inventory, time, nearby }
      args TEXT NOT NULL DEFAULT '{}',
      error TEXT,
      FOREIGN KEY (skill_name) REFERENCES skills(name)
    );
    CREATE INDEX IF NOT EXISTS idx_trials_skill_name ON skill_trials(skill_name);
  `);

  // Migrations: ALTER COLUMN if missing on existing DBs.
  const cols = handle.prepare("PRAGMA table_info('skills')").all() as Array<{ name: string }>;
  if (!cols.some((c) => c.name === 'verified')) {
    handle.exec("ALTER TABLE skills ADD COLUMN verified INTEGER NOT NULL DEFAULT 0");
    handle.exec('UPDATE skills SET verified = 1 WHERE success_count > 0');
  }
  if (!cols.some((c) => c.name === 'consecutive_failures')) {
    handle.exec("ALTER TABLE skills ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0");
  }
  if (!cols.some((c) => c.name === 'prerequisites')) {
    handle.exec("ALTER TABLE skills ADD COLUMN prerequisites TEXT NOT NULL DEFAULT '[]'");
  }
  if (!cols.some((c) => c.name === 'produces_items')) {
    handle.exec("ALTER TABLE skills ADD COLUMN produces_items TEXT NOT NULL DEFAULT '[]'");
  }

  const stored = handle
    .prepare<[], { value: string }>("SELECT value FROM meta WHERE key = 'schema_version'")
    .get();

  if (!stored) {
    handle
      .prepare("INSERT INTO meta (key, value) VALUES ('schema_version', ?)")
      .run(String(SCHEMA_VERSION));
  } else if (Number(stored.value) !== SCHEMA_VERSION) {
    // The DDL above runs CREATE TABLE IF NOT EXISTS + the v2→v3 ALTER
    // (verified column). That's all the migration we need for v1→v3.
    // Just bump the meta row to acknowledge.
    handle
      .prepare("UPDATE meta SET value = ? WHERE key = 'schema_version'")
      .run(String(SCHEMA_VERSION));
    logs.mem.info(
      { from: stored.value, to: SCHEMA_VERSION },
      'schema migrated'
    );
  }
}

// --- Embedding (de)serialization ---
// Float32Array <-> Buffer in native byte order. sqlite-vec's vec_distance_*
// functions accept either BLOB-of-floats or JSON arrays; BLOB is faster.

export function encodeEmbedding(vec: number[]): Buffer {
  if (vec.length !== EMBED_DIM) {
    throw new Error(`embedding dim ${vec.length} != expected ${EMBED_DIM}`);
  }
  const f32 = new Float32Array(vec);
  return Buffer.from(f32.buffer, f32.byteOffset, f32.byteLength);
}

export function decodeEmbedding(buf: Buffer): Float32Array {
  // Buffer view → Float32Array. byteOffset alignment matters; copy if odd.
  if (buf.byteOffset % 4 === 0 && buf.byteLength % 4 === 0) {
    return new Float32Array(buf.buffer, buf.byteOffset, buf.byteLength / 4);
  }
  const aligned = Buffer.from(buf);
  return new Float32Array(aligned.buffer, aligned.byteOffset, aligned.byteLength / 4);
}

// Helpers for callers that don't want to hold the DB handle.
export function getDb(): DB {
  if (!db) throw new Error('memory store not opened; call openMemoryStore() first');
  return db;
}

export function nowIso(): string {
  return new Date().toISOString();
}
