// Multi-turn goal stack — T1.1 from COMPANION_V2_ROADMAP.md.
//
// Grounded in GROOT-2 (arXiv 2412.10410) and Optimus-3 (arXiv 2506.10357),
// both of which emphasize long-horizon goal-conditioned policies. Our
// previous planner had no cross-turn state, so the LLM kept losing the
// player's original ask after iterating on a sub-task.
//
// Each goal has:
//   - text:     short natural-language description of what the player asked
//   - status:   active | done | cancelled
//   - parent:   optional id, for sub-goals decomposed by Steve
//
// Active goals are auto-injected into the bot-state snapshot each turn.
// Sub-goals nest under their parent for visual indentation.

import { getDb, nowIso } from './store.js';
import { logs } from '../log.js';

export type GoalStatus = 'active' | 'done' | 'cancelled';

export interface Goal {
  id: number;
  ts: string;
  text: string;
  status: GoalStatus;
  parentId: number | null;
  tsDone: string | null;
}

interface GoalRow {
  id: number;
  ts: string;
  text: string;
  status: string;
  parent_id: number | null;
  ts_done: string | null;
}

function rowToGoal(r: GoalRow): Goal {
  return {
    id: r.id,
    ts: r.ts,
    text: r.text,
    status: r.status as GoalStatus,
    parentId: r.parent_id,
    tsDone: r.ts_done,
  };
}

export class GoalStack {
  /** Push a new active goal. Optionally a sub-goal of an existing parent. */
  push(text: string, parentId?: number): Goal {
    const ts = nowIso();
    const stmt = getDb().prepare<
      [string, string, string, number | null],
      GoalRow
    >(
      `INSERT INTO goals (ts, text, status, parent_id)
       VALUES (?, ?, ?, ?)
       RETURNING id, ts, text, status, parent_id, ts_done`
    );
    const row = stmt.get(ts, text, 'active', parentId ?? null);
    if (!row) throw new Error('goal insert returned no row');
    logs.plan.info({ goal: row.id, text: row.text, parent: row.parent_id }, 'goal pushed');
    return rowToGoal(row);
  }

  /** Mark a goal done. If the goal has active subgoals, those go done too. */
  complete(id: number): Goal | null {
    const ts = nowIso();
    getDb()
      .prepare<[string, number]>(
        "UPDATE goals SET status = 'done', ts_done = ? WHERE id = ? AND status = 'active'"
      )
      .run(ts, id);
    // Cascade: any active sub-goal of this one is also done.
    getDb()
      .prepare<[string, number]>(
        "UPDATE goals SET status = 'done', ts_done = ? WHERE parent_id = ? AND status = 'active'"
      )
      .run(ts, id);
    const row = this.getRow(id);
    if (row) logs.plan.info({ goal: id }, 'goal completed');
    return row ? rowToGoal(row) : null;
  }

  /** Cancel a goal (and its sub-goals). */
  cancel(id: number): Goal | null {
    const ts = nowIso();
    getDb()
      .prepare<[string, number]>(
        "UPDATE goals SET status = 'cancelled', ts_done = ? WHERE id = ? AND status = 'active'"
      )
      .run(ts, id);
    getDb()
      .prepare<[string, number]>(
        "UPDATE goals SET status = 'cancelled', ts_done = ? WHERE parent_id = ? AND status = 'active'"
      )
      .run(ts, id);
    const row = this.getRow(id);
    return row ? rowToGoal(row) : null;
  }

  /** Currently active goals (parents only, with sub-goals attached). */
  active(): Goal[] {
    const rows = getDb()
      .prepare<[], GoalRow>(
        `SELECT id, ts, text, status, parent_id, ts_done
         FROM goals WHERE status = 'active' ORDER BY id ASC`
      )
      .all();
    return rows.map(rowToGoal);
  }

  /** Render the active goal stack as one-line summary for bot-state injection. */
  renderForContext(): string {
    const goals = this.active();
    if (goals.length === 0) return 'no active goals';
    // Build parent→children adjacency for nesting.
    const byParent = new Map<number | null, Goal[]>();
    for (const g of goals) {
      const arr = byParent.get(g.parentId) ?? [];
      arr.push(g);
      byParent.set(g.parentId, arr);
    }
    const lines: string[] = [];
    const roots = byParent.get(null) ?? [];
    for (const root of roots) {
      lines.push(`[#${root.id}] ${root.text}`);
      const subs = byParent.get(root.id) ?? [];
      for (const sub of subs) {
        lines.push(`   └─ [#${sub.id}] ${sub.text}`);
      }
    }
    return lines.join('\n');
  }

  get(id: number): Goal | null {
    const row = this.getRow(id);
    return row ? rowToGoal(row) : null;
  }

  private getRow(id: number): GoalRow | null {
    const row = getDb()
      .prepare<[number], GoalRow>(
        `SELECT id, ts, text, status, parent_id, ts_done FROM goals WHERE id = ?`
      )
      .get(id);
    return row ?? null;
  }
}

export const goals = new GoalStack();
