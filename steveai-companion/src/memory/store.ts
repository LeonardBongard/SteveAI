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
const SCHEMA_VERSION = 1;

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
  `);

  const stored = handle
    .prepare<[], { value: string }>("SELECT value FROM meta WHERE key = 'schema_version'")
    .get();

  if (!stored) {
    handle
      .prepare("INSERT INTO meta (key, value) VALUES ('schema_version', ?)")
      .run(String(SCHEMA_VERSION));
  } else if (Number(stored.value) !== SCHEMA_VERSION) {
    // No migrations yet — when v2 lands, branch here.
    logs.mem.warn(
      { stored: stored.value, expected: SCHEMA_VERSION },
      'schema version mismatch; no migration available'
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
