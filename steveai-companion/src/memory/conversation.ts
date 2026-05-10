// Conversation memory: sliding window + pinned facts + vector retrieval.
//
// Per docs/COMPANION_V1_DIRECTION.md §3.3 / §3.7 #8: we deliberately do NOT
// LLM-summarize. Summarization throws away the specifics that make a
// companion feel like it remembers you. Instead:
//
// - Last N turns kept verbatim (the "window") for the LLM to see directly.
// - Pinned facts: stable preferences/landmarks the player has stated
//   ("I prefer oak", "my base is at -123 64 456"). Always sent in context.
// - Older turns: embedded; retrieved by similarity when relevant.
//
// All three layers cost almost nothing per turn. The retrieval pass is
// embedding-rank only (tier 1) — see §3.7 #6 for the optional rerank/full-
// LLM tiers, those are caller decisions, not encoded here.

import { embed } from '../llm/ollama.js';
import { getDb, nowIso, encodeEmbedding, EMBED_DIM } from './store.js';
import { logs } from '../log.js';

export interface ConversationTurn {
  id: number;
  ts: string;
  speaker: string;     // 'player:<name>' or 'steve'
  message: string;
}

export interface PinnedFact {
  key: string;         // e.g. 'preference:wood_type', 'location:home'
  value: string;
  context: string | null;
  ts: string;
}

export class ConversationMemory {
  /**
   * Record a turn. Embeds asynchronously so the call returns quickly;
   * the embedding is patched in once Ollama responds. We do NOT block
   * the turn handler on embed completion.
   */
  async recordTurn(speaker: string, message: string): Promise<ConversationTurn> {
    const ts = nowIso();
    const insert = getDb().prepare<[string, string, string], ConversationTurn>(`
      INSERT INTO conversation (ts, speaker, message)
      VALUES (?, ?, ?)
      RETURNING id, ts, speaker, message
    `);
    const row = insert.get(ts, speaker, message);
    if (!row) throw new Error('conversation insert returned no row');

    // Fire-and-forget embed. If it fails, the turn is still in the DB —
    // it just won't be retrievable by similarity. Acceptable degradation.
    void this.embedTurn(row.id, message).catch((err) =>
      logs.mem.warn({ err: (err as Error).message, id: row.id }, 'embed turn failed')
    );

    return row;
  }

  private async embedTurn(id: number, message: string): Promise<void> {
    const vec = await embed(message);
    const blob = encodeEmbedding(vec);
    getDb().prepare<[Buffer, number]>('UPDATE conversation SET embedding = ? WHERE id = ?').run(blob, id);
  }

  /** Most recent N turns, oldest-first (the order to feed the LLM). */
  window(n: number): ConversationTurn[] {
    const recent = getDb()
      .prepare<[number], ConversationTurn>(
        'SELECT id, ts, speaker, message FROM conversation ORDER BY id DESC LIMIT ?'
      )
      .all(n);
    return recent.reverse();
  }

  /**
   * Top-K turns by embedding similarity, EXCLUDING the most-recent windowSize
   * turns (those already shown to the LLM in window()). Returns nothing if
   * the embed call fails.
   */
  async retrieveSimilar(
    query: string,
    k: number,
    windowSize: number
  ): Promise<ConversationTurn[]> {
    let queryVec: number[];
    try {
      queryVec = await embed(query);
    } catch (err) {
      logs.mem.warn({ err: (err as Error).message }, 'retrieval embed failed; returning []');
      return [];
    }
    if (queryVec.length !== EMBED_DIM) {
      logs.mem.warn(
        { dim: queryVec.length, expected: EMBED_DIM },
        'retrieval embed wrong dim; returning []'
      );
      return [];
    }
    const queryBlob = encodeEmbedding(queryVec);

    // Find the cutoff id below which we'll search (older than the window).
    const cutoffRow = getDb()
      .prepare<[number], { id: number }>(
        'SELECT id FROM conversation ORDER BY id DESC LIMIT 1 OFFSET ?'
      )
      .get(windowSize);
    const cutoffId = cutoffRow?.id ?? 0;

    return getDb()
      .prepare<[number, Buffer, number], ConversationTurn>(
        `SELECT id, ts, speaker, message
         FROM conversation
         WHERE embedding IS NOT NULL AND id <= ?
         ORDER BY vec_distance_cosine(embedding, ?) ASC
         LIMIT ?`
      )
      .all(cutoffId, queryBlob, k);
  }

  // --- Pinned facts ---

  pinFact(key: string, value: string, context?: string): void {
    const ts = nowIso();
    getDb()
      .prepare<[string, string, string | null, string]>(
        `INSERT INTO pinned_facts (key, value, context, ts)
         VALUES (?, ?, ?, ?)
         ON CONFLICT(key) DO UPDATE SET
           value = excluded.value,
           context = excluded.context,
           ts = excluded.ts`
      )
      .run(key, value, context ?? null, ts);
    logs.mem.debug({ key, value }, 'pinned fact');
  }

  unpinFact(key: string): boolean {
    const info = getDb().prepare<[string]>('DELETE FROM pinned_facts WHERE key = ?').run(key);
    return info.changes > 0;
  }

  pinnedFacts(): PinnedFact[] {
    return getDb()
      .prepare<[], PinnedFact>(
        'SELECT key, value, context, ts FROM pinned_facts ORDER BY key ASC'
      )
      .all();
  }

  pinnedFact(key: string): PinnedFact | null {
    const row = getDb()
      .prepare<[string], PinnedFact>(
        'SELECT key, value, context, ts FROM pinned_facts WHERE key = ?'
      )
      .get(key);
    return row ?? null;
  }
}

export const conversation = new ConversationMemory();
