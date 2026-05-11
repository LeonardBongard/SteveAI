// Skill-trial experience pool — T2.1 from COMPANION_V2_ROADMAP.md.
//
// Snapshot of world state + outcome each time a skill is invoked. Inspired by
// Optimus-1's experience pool: a skill is more useful when you also know
// WHEN/WHERE it tends to work. We can later condition retrieval on
// "this skill worked when inventory had X" rather than just "this skill
// exists." For now we just persist; consumers (retrieval, after-action,
// the LLM's own learning) come next.

import type { Bot } from 'mineflayer';
import { getDb, nowIso } from './store.js';
import { logs } from '../log.js';

export interface TrialSnapshot {
  position: { x: number; y: number; z: number } | null;
  dimension: string;
  inventory: Array<{ name: string; count: number }>;
  health: number | null;
  food: number | null;
  time: { day: boolean; tick: number | null } | null;
  nearbyUtilities: string[]; // names of utility blocks within 24 blocks
}

export interface SkillTrial {
  id: number;
  ts: string;
  skillName: string;
  ok: boolean;
  durationMs: number | null;
  stateBefore: TrialSnapshot;
  args: Record<string, unknown>;
  error: string | null;
}

interface TrialRow {
  id: number;
  ts: string;
  skill_name: string;
  ok: number;
  duration_ms: number | null;
  state_before: string;
  args: string;
  error: string | null;
}

const UTILITY_NAMES = [
  'crafting_table',
  'furnace',
  'blast_furnace',
  'smoker',
  'chest',
  'anvil',
  'enchanting_table',
  'brewing_stand',
];

/**
 * Capture a compact snapshot of the bot's current world state. Designed to
 * be cheap enough to run on every skill invocation (no raycasts, just
 * inventory + position + a couple of findBlock calls).
 */
export function captureSnapshot(bot: Bot): TrialSnapshot {
  const p = bot.entity?.position ?? null;
  const inventory: Array<{ name: string; count: number }> = (() => {
    try {
      const items = bot.inventory?.items() ?? [];
      return items.map((i) => ({ name: i.name, count: i.count }));
    } catch {
      return [];
    }
  })();
  const nearbyUtilities: string[] = (() => {
    try {
      const reg = bot.registry?.blocksByName;
      if (!reg) return [];
      const out: string[] = [];
      for (const name of UTILITY_NAMES) {
        const def = reg[name];
        if (!def) continue;
        const block = bot.findBlock?.({ matching: def.id, maxDistance: 24 });
        if (block) out.push(name);
      }
      return out;
    } catch {
      return [];
    }
  })();
  return {
    position: p ? { x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) } : null,
    dimension: bot.game?.dimension ?? 'overworld',
    inventory,
    health: typeof bot.health === 'number' ? bot.health : null,
    food: typeof bot.food === 'number' ? bot.food : null,
    time: bot.time
      ? { day: bot.time.isDay, tick: bot.time.timeOfDay ?? bot.time.time ?? null }
      : null,
    nearbyUtilities,
  };
}

function rowToTrial(r: TrialRow): SkillTrial {
  return {
    id: r.id,
    ts: r.ts,
    skillName: r.skill_name,
    ok: r.ok === 1,
    durationMs: r.duration_ms,
    stateBefore: safeParseSnapshot(r.state_before),
    args: safeParseObj(r.args),
    error: r.error,
  };
}

function safeParseSnapshot(raw: string): TrialSnapshot {
  try {
    return JSON.parse(raw) as TrialSnapshot;
  } catch {
    return {
      position: null,
      dimension: 'overworld',
      inventory: [],
      health: null,
      food: null,
      time: null,
      nearbyUtilities: [],
    };
  }
}

function safeParseObj(raw: string): Record<string, unknown> {
  try {
    const v = JSON.parse(raw);
    return v && typeof v === 'object' ? (v as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

export class SkillTrialStore {
  /**
   * Persist a single trial. Called immediately after a skill finishes (ok or
   * not). state_before is the snapshot captured BEFORE invocation.
   */
  record(args: {
    skillName: string;
    ok: boolean;
    durationMs: number | null;
    stateBefore: TrialSnapshot;
    args: Record<string, unknown>;
    error?: string | null;
  }): SkillTrial | null {
    try {
      const stmt = getDb().prepare<
        [string, string, number, number | null, string, string, string | null],
        TrialRow
      >(
        `INSERT INTO skill_trials (ts, skill_name, ok, duration_ms, state_before, args, error)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         RETURNING id, ts, skill_name, ok, duration_ms, state_before, args, error`
      );
      const row = stmt.get(
        nowIso(),
        args.skillName,
        args.ok ? 1 : 0,
        args.durationMs ?? null,
        JSON.stringify(args.stateBefore),
        JSON.stringify(args.args ?? {}),
        args.error ?? null
      );
      return row ? rowToTrial(row) : null;
    } catch (err) {
      logs.mem.warn({ err: (err as Error).message, skill: args.skillName }, 'skill trial insert failed');
      return null;
    }
  }

  recent(limit = 10, skillName?: string): SkillTrial[] {
    if (skillName) {
      const rows = getDb()
        .prepare<[string, number], TrialRow>(
          `SELECT id, ts, skill_name, ok, duration_ms, state_before, args, error
           FROM skill_trials WHERE skill_name = ? ORDER BY id DESC LIMIT ?`
        )
        .all(skillName, limit);
      return rows.map(rowToTrial);
    }
    const rows = getDb()
      .prepare<[number], TrialRow>(
        `SELECT id, ts, skill_name, ok, duration_ms, state_before, args, error
         FROM skill_trials ORDER BY id DESC LIMIT ?`
      )
      .all(limit);
    return rows.map(rowToTrial);
  }

  /**
   * Compact one-line per trial. Used by the after-action review (T2.4) and
   * by future retrieval to give the LLM "this skill last worked when X was
   * in inventory" signal.
   */
  format(trial: SkillTrial): string {
    const ok = trial.ok ? '✓' : '✗';
    const dur = trial.durationMs !== null ? `${trial.durationMs}ms` : '?ms';
    const invSummary = trial.stateBefore.inventory.length === 0
      ? 'empty inv'
      : `inv=${trial.stateBefore.inventory.slice(0, 6).map((i) => `${i.count}x ${i.name}`).join(',')}`;
    const utils = trial.stateBefore.nearbyUtilities.length > 0
      ? ` near=${trial.stateBefore.nearbyUtilities.join(',')}`
      : '';
    const err = !trial.ok && trial.error ? ` err="${trial.error.slice(0, 60)}"` : '';
    return `${ok} ${trial.skillName} ${dur} (${invSummary}${utils})${err}`;
  }
}

export const skillTrials = new SkillTrialStore();
