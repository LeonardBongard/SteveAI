// World event feed — T2.2 from COMPANION_V2_ROADMAP.md.
//
// In-memory ring buffer of recent mineflayer events. The bot subscribes to
// useful events (death, hurt, low food/health, item picked up, day→night,
// mob aggro nearby) and pushes one-line summaries into the buffer. The
// buffer is read each turn and injected into the bot-state snapshot under
// [ENV], so the LLM sees recent world events without needing a tool call.
//
// Capped so it doesn't grow forever; older entries fall off.

import type { Bot } from 'mineflayer';
import { logs } from '../log.js';

interface WorldEvent {
  ts: number;
  line: string;
}

const MAX_BUFFER = 30;
/** Events older than this are dropped on read, even if buffer isn't full. */
const STALE_MS = 5 * 60 * 1000;

export class WorldEventFeed {
  private buffer: WorldEvent[] = [];
  private attachedBot: Bot | null = null;
  private lastHealth: number | null = null;
  private lastFood: number | null = null;
  private lastDay: boolean | null = null;
  private healthCooldownUntil = 0;
  private foodCooldownUntil = 0;
  private dayCycleCooldownUntil = 0;

  /**
   * Subscribe to mineflayer events on this bot. Safe to call once after the
   * bot has spawned. No-op on subsequent calls (we don't want to double-wire).
   */
  attach(bot: Bot): void {
    if (this.attachedBot) return;
    this.attachedBot = bot;

    bot.on('death', () => {
      const p = bot.entity?.position;
      const where = p ? `(${Math.round(p.x)}, ${Math.round(p.y)}, ${Math.round(p.z)})` : '?';
      this.push(`died at ${where}`);
    });

    bot.on('respawn', () => {
      this.push('respawned');
    });

    bot.on('entityHurt', (entity) => {
      if (entity?.id !== bot.entity?.id) return;
      const hp = typeof bot.health === 'number' ? `${Math.round(bot.health * 10) / 10}/20` : '?';
      this.push(`took damage; health now ${hp}`);
    });

    bot.on('playerCollect', (collector, collected) => {
      if (collector?.username !== bot.username) return;
      const itemName = (collected as { metadata?: unknown }).metadata
        ? 'item'
        : 'item';
      this.push(`picked up ${itemName}`);
    });

    // Health / food thresholds. Throttled so we don't spam the same warning
    // every tick when food keeps draining.
    bot.on('health', () => {
      const now = Date.now();
      const hp = bot.health;
      const food = bot.food;
      if (typeof hp === 'number' && hp <= 6 && now >= this.healthCooldownUntil) {
        this.push(`LOW HEALTH (${Math.round(hp)}/20)`);
        this.healthCooldownUntil = now + 30 * 1000;
      }
      if (typeof food === 'number' && food <= 6 && now >= this.foodCooldownUntil) {
        this.push(`LOW FOOD (${food}/20)`);
        this.foodCooldownUntil = now + 30 * 1000;
      }
      this.lastHealth = typeof hp === 'number' ? hp : this.lastHealth;
      this.lastFood = typeof food === 'number' ? food : this.lastFood;
    });

    // Day ↔ night transition.
    bot.on('time', () => {
      const isDay = bot.time?.isDay;
      if (typeof isDay !== 'boolean') return;
      if (this.lastDay === null) {
        this.lastDay = isDay;
        return;
      }
      const now = Date.now();
      if (isDay !== this.lastDay && now >= this.dayCycleCooldownUntil) {
        this.push(isDay ? 'sunrise — daytime now' : 'sunset — nighttime now');
        this.lastDay = isDay;
        this.dayCycleCooldownUntil = now + 60 * 1000;
      }
    });

    logs.mem.info('world event feed attached');
  }

  /**
   * Push a free-form event line. Public so other subsystems (e.g. the
   * planner's skill log) can record significant beats too.
   */
  push(line: string): void {
    this.buffer.push({ ts: Date.now(), line });
    if (this.buffer.length > MAX_BUFFER) {
      this.buffer.splice(0, this.buffer.length - MAX_BUFFER);
    }
  }

  /**
   * Recent events as one-line strings, oldest-first. Drops stale entries on
   * read. Does NOT clear the buffer — the planner reads this every turn.
   */
  recent(limit = 6): string[] {
    const cutoff = Date.now() - STALE_MS;
    this.buffer = this.buffer.filter((e) => e.ts >= cutoff);
    const slice = this.buffer.slice(-limit);
    return slice.map((e) => {
      const ageS = Math.max(0, Math.round((Date.now() - e.ts) / 1000));
      return `${ageS}s ago: ${e.line}`;
    });
  }

  /**
   * Empty the buffer. Currently unused — kept for tests / explicit reset.
   */
  clear(): void {
    this.buffer = [];
  }
}

export const worldEvents = new WorldEventFeed();
