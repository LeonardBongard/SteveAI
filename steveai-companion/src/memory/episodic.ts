// Episodic place memory — Place Event Memory (PEM) per [MrSteve, ICLR 2025].
// Stored as (what, where, when) tuples ~50 bytes each, ~100× smaller than
// keeping raw chat / action transcripts.
//
// We do not embed episodic events (most queries are spatial / event-type
// based, not semantic). If we end up with semantic queries like "places
// where I felt unsafe", embed `context` lazily — schema already has room.

import { getDb, nowIso } from './store.js';
import { logs } from '../log.js';

export interface EpisodicEvent {
  id: number;
  ts: string;
  event: string;       // short tag: 'mined_iron', 'found_cave', 'died', 'crafted_pickaxe'
  x: number;
  y: number;
  z: number;
  dimension: string;   // 'overworld', 'the_nether', 'the_end'
  context: string;     // 1-line human description for prompt injection
}

export type NewEpisodicEvent = Omit<EpisodicEvent, 'id' | 'ts'>;

export class EpisodicMemory {
  recordEvent(e: NewEpisodicEvent): EpisodicEvent {
    const ts = nowIso();
    const stmt = getDb().prepare<
      [string, string, number, number, number, string, string],
      EpisodicEvent
    >(`
      INSERT INTO episodic (ts, event, x, y, z, dimension, context)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      RETURNING id, ts, event, x, y, z, dimension, context
    `);
    const row = stmt.get(ts, e.event, e.x, e.y, e.z, e.dimension, e.context);
    if (!row) throw new Error('episodic insert returned no row');
    logs.mem.debug({ event: row.event, xyz: [row.x, row.y, row.z] }, 'episodic recorded');
    return row;
  }

  /** Recent events, optionally filtered by event tag. */
  recent(opts: { event?: string; limit?: number } = {}): EpisodicEvent[] {
    const limit = opts.limit ?? 10;
    if (opts.event !== undefined) {
      return getDb()
        .prepare<[string, number], EpisodicEvent>(
          `SELECT id, ts, event, x, y, z, dimension, context
           FROM episodic WHERE event = ? ORDER BY ts DESC LIMIT ?`
        )
        .all(opts.event, limit);
    }
    return getDb()
      .prepare<[number], EpisodicEvent>(
        `SELECT id, ts, event, x, y, z, dimension, context
         FROM episodic ORDER BY ts DESC LIMIT ?`
      )
      .all(limit);
  }

  /** Events within a Chebyshev (max-axis) distance — fast index-friendly box. */
  nearby(
    center: { x: number; y: number; z: number; dimension: string },
    radius: number,
    limit = 10
  ): EpisodicEvent[] {
    return getDb()
      .prepare<[string, number, number, number, number, number, number, number], EpisodicEvent>(
        `SELECT id, ts, event, x, y, z, dimension, context
         FROM episodic
         WHERE dimension = ?
           AND x BETWEEN ? AND ?
           AND y BETWEEN ? AND ?
           AND z BETWEEN ? AND ?
         ORDER BY ts DESC
         LIMIT ?`
      )
      .all(
        center.dimension,
        center.x - radius,
        center.x + radius,
        center.y - radius,
        center.y + radius,
        center.z - radius,
        center.z + radius,
        limit
      );
  }

  /** Most recent event matching tag — useful for "the cave you found yesterday". */
  lastOf(event: string): EpisodicEvent | null {
    const row = getDb()
      .prepare<[string], EpisodicEvent>(
        `SELECT id, ts, event, x, y, z, dimension, context
         FROM episodic WHERE event = ? ORDER BY ts DESC LIMIT 1`
      )
      .get(event);
    return row ?? null;
  }

  /** Diagnostics. */
  count(): number {
    const row = getDb().prepare<[], { c: number }>('SELECT COUNT(*) AS c FROM episodic').get();
    return row?.c ?? 0;
  }
}

export const episodic = new EpisodicMemory();
