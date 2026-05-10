// SQLite-backed skill library — v2.
//
// Stores executable JavaScript that the LLM has written, named, and
// (ideally) verified. Embedded by description for retrieval. Tracks
// success and failure counts. Schema is in memory/store.ts (skills table).
//
// API:
//   save(name, description, code)        → embed + persist
//   get(name)                             → exact lookup
//   search(query, k)                      → vector-similarity retrieval
//   recordSuccess(name) / recordFailure(name, error)
//   list(limit)                           → recent + most-used

import { embed } from '../llm/ollama.js';
import { getDb, nowIso, encodeEmbedding } from '../memory/store.js';
import { logs } from '../log.js';

export interface Skill {
  id: number;
  ts: string;
  name: string;
  description: string;
  code: string;
  successCount: number;
  failureCount: number;
  lastInvokedAt: string | null;
  verified: boolean;
}

interface SkillRow {
  id: number;
  ts: string;
  name: string;
  description: string;
  code: string;
  success_count: number;
  failure_count: number;
  last_invoked_at: string | null;
  verified: number;
}

function rowToSkill(r: SkillRow): Skill {
  return {
    id: r.id,
    ts: r.ts,
    name: r.name,
    description: r.description,
    code: r.code,
    successCount: r.success_count,
    failureCount: r.failure_count,
    lastInvokedAt: r.last_invoked_at,
    verified: r.verified === 1,
  };
}

/** Thrown by save() when the LLM tries to overwrite a verified skill. */
export class VerifiedSkillExistsError extends Error {
  constructor(public readonly existingName: string, public readonly successCount: number) {
    super(
      `skill "${existingName}" already exists, is verified (success_count=${successCount}), and won't be overwritten. Pick a new name (e.g. "${existingName}_v2") or invokeSkill("${existingName}") if you want to use it.`
    );
    this.name = 'VerifiedSkillExistsError';
  }
}

export class SkillLibrary {
  /**
   * Persist a new skill.
   *
   * Behavior (P5 versioning):
   *  - If no skill with this name exists → insert (verified=0).
   *  - If an UNVERIFIED skill exists with this name → overwrite in place.
   *    This is the DEPS retry path: LLM is iterating on a not-yet-working
   *    skill.
   *  - If a VERIFIED skill exists with this name → throw
   *    VerifiedSkillExistsError. The LLM must pick a new name to evolve
   *    a working skill.
   */
  async save(name: string, description: string, code: string): Promise<Skill> {
    const ts = nowIso();
    const cleanName = sanitizeName(name);
    if (!cleanName) throw new Error(`invalid skill name: "${name}"`);

    const existing = this.getRow(cleanName);
    if (existing && existing.verified === 1) {
      throw new VerifiedSkillExistsError(cleanName, existing.success_count);
    }

    const upsert = getDb().prepare<
      [string, string, string, string],
      SkillRow
    >(`
      INSERT INTO skills (ts, name, description, code, success_count, failure_count, verified)
      VALUES (?, ?, ?, ?, 0, 0, 0)
      ON CONFLICT(name) DO UPDATE SET
        ts = excluded.ts,
        description = excluded.description,
        code = excluded.code
      RETURNING id, ts, name, description, code, success_count, failure_count, last_invoked_at, verified
    `);
    const row = upsert.get(ts, cleanName, description, code);
    if (!row) throw new Error(`skill insert returned no row for ${cleanName}`);

    // Embed the description so we can retrieve later. Fire-and-forget.
    void this.embed(row.id, description).catch((err) =>
      logs.mem.warn({ err: (err as Error).message, name: cleanName }, 'embed skill failed')
    );

    logs.act.info({ skill: cleanName, ts }, 'skill saved (unverified)');
    return rowToSkill(row);
  }

  /**
   * Mark a skill as verified after at least one successful invocation.
   * Once verified, save() refuses to overwrite (P5).
   */
  markVerified(name: string): void {
    const cleanName = sanitizeName(name);
    getDb()
      .prepare<[string]>(
        'UPDATE skills SET verified = 1 WHERE name = ? AND verified = 0'
      )
      .run(cleanName);
  }

  private getRow(name: string): SkillRow | null {
    const row = getDb()
      .prepare<[string], SkillRow>(
        `SELECT id, ts, name, description, code, success_count, failure_count, last_invoked_at, verified
         FROM skills WHERE name = ?`
      )
      .get(name);
    return row ?? null;
  }

  get(name: string): Skill | null {
    const cleanName = sanitizeName(name);
    const row = this.getRow(cleanName);
    return row ? rowToSkill(row) : null;
  }

  /**
   * Vector retrieval over description embedding. Verified skills first, then
   * unverified, both ordered by similarity. The LLM is told to prefer
   * verified hits in the prompt.
   */
  async search(query: string, k = 4): Promise<Skill[]> {
    let queryVec: number[];
    try {
      queryVec = await embed(query);
    } catch (err) {
      logs.mem.warn({ err: (err as Error).message }, 'skill search embed failed');
      return [];
    }
    const queryBlob = encodeEmbedding(queryVec);

    const rows = getDb()
      .prepare<[Buffer, number], SkillRow>(
        `SELECT id, ts, name, description, code, success_count, failure_count, last_invoked_at, verified
         FROM skills
         WHERE embedding IS NOT NULL
         ORDER BY verified DESC, vec_distance_cosine(embedding, ?) ASC
         LIMIT ?`
      )
      .all(queryBlob, k);

    return rows.map(rowToSkill);
  }

  /** Track success after a successful invocation. Light-touch; doesn't fail. */
  recordSuccess(name: string): void {
    const cleanName = sanitizeName(name);
    const ts = nowIso();
    getDb()
      .prepare<[string, string]>(
        `UPDATE skills
         SET success_count = success_count + 1,
             last_invoked_at = ?
         WHERE name = ?`
      )
      .run(ts, cleanName);
  }

  recordFailure(name: string, error: string): void {
    const cleanName = sanitizeName(name);
    const ts = nowIso();
    getDb()
      .prepare<[string, string]>(
        `UPDATE skills
         SET failure_count = failure_count + 1,
             last_invoked_at = ?
         WHERE name = ?`
      )
      .run(ts, cleanName);
    logs.act.warn({ skill: cleanName, error }, 'skill recorded failure');
  }

  list(limit = 50): Skill[] {
    const rows = getDb()
      .prepare<[number], SkillRow>(
        `SELECT id, ts, name, description, code, success_count, failure_count, last_invoked_at, verified
         FROM skills
         ORDER BY verified DESC, (success_count - failure_count) DESC, ts DESC
         LIMIT ?`
      )
      .all(limit);
    return rows.map(rowToSkill);
  }

  count(): number {
    const row = getDb()
      .prepare<[], { c: number }>('SELECT COUNT(*) AS c FROM skills')
      .get();
    return row?.c ?? 0;
  }

  // --- internals ---

  private async embed(id: number, description: string): Promise<void> {
    const vec = await embed(description);
    const blob = encodeEmbedding(vec);
    getDb()
      .prepare<[Buffer, number]>('UPDATE skills SET embedding = ? WHERE id = ?')
      .run(blob, id);
  }
}

function sanitizeName(raw: string): string {
  return raw
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 60);
}

export const skills = new SkillLibrary();
