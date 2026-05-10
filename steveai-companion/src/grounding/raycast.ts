// Spatial deixis via raycast — "that ore", "this tree", "the X I'm looking at".
//
// Per docs/COMPANION_V1_DIRECTION.md §3.4: we resolve precise references
// against Mineflayer's structured world state, not pixels. For deictic
// references the player makes ("that"), we cast a ray from THEIR eye position
// in their look direction and return the first non-air block hit (and / or
// any entity intersected). Sub-millisecond, bit-exact, no ML.

import type { Bot } from 'mineflayer';
import type { Block } from 'prismarine-block';
import type { Entity } from 'prismarine-entity';
import { Vec3 } from 'vec3';
import { logs } from '../log.js';

const DEFAULT_MAX_DISTANCE = 64;
const PLAYER_EYE_OFFSET_Y = 1.62; // standard eye height

export interface RaycastHit {
  block: Block | null;
  entity: Entity | null;
  position: Vec3;        // hit point (block face center for blocks, entity origin for entities)
  origin: Vec3;          // where the ray started (player eye)
  direction: Vec3;       // normalized look vector
  source: 'block' | 'entity' | 'miss';
}

/** Look direction from a yaw/pitch pair (Mineflayer/Minecraft convention). */
export function lookVector(yaw: number, pitch: number): Vec3 {
  // Mineflayer's yaw is rotation around Y; 0 faces +Z (south), increasing toward +X (west).
  // Pitch is positive looking down. Standard MC math:
  const cosPitch = Math.cos(pitch);
  return new Vec3(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), -Math.cos(yaw) * cosPitch);
}

/**
 * Cast a ray from a player's eye in the direction they're facing. Returns
 * the first block hit, or any entity along the ray (within entityRadius).
 * If the player isn't visible to the bot, returns 'miss'.
 */
export function raycastFromPlayer(
  bot: Bot,
  playerName: string,
  opts: { maxDistance?: number; entityRadius?: number } = {}
): RaycastHit {
  const max = opts.maxDistance ?? DEFAULT_MAX_DISTANCE;
  const entityR = opts.entityRadius ?? 1.0;

  const player = bot.players[playerName]?.entity;
  if (!player) {
    logs.ground.debug({ playerName }, 'raycast: player entity not visible');
    return missHit();
  }

  const origin = player.position.offset(0, PLAYER_EYE_OFFSET_Y, 0);
  const dir = lookVector(player.yaw, player.pitch);

  // Block raycast — Mineflayer's iterator under the hood. blockAtCursor uses
  // the BOT's eye; we want the PLAYER's, so go through prismarine-world's
  // raycast directly.
  let block: Block | null = null;
  try {
    // bot.world.raycast is provided by prismarine-world.
    // Signature: (origin, direction, range, matcher?) => Block | null
    const w = bot.world as unknown as {
      raycast(o: Vec3, d: Vec3, r: number): Block | null;
    };
    if (typeof w.raycast === 'function') {
      block = w.raycast(origin, dir, max);
    }
  } catch (err) {
    logs.ground.warn({ err: (err as Error).message }, 'raycast: block trace failed');
  }

  // Entity intersection — anything within entityR of the ray, up to the block hit.
  const blockDist = block ? origin.distanceTo(block.position) : max;
  const entity = nearestEntityAlongRay(bot, origin, dir, blockDist, entityR, playerName);

  if (entity && (!block || origin.distanceTo(entity.position) < blockDist)) {
    return {
      block: null,
      entity,
      position: entity.position,
      origin,
      direction: dir,
      source: 'entity',
    };
  }
  if (block) {
    return {
      block,
      entity: null,
      position: block.position,
      origin,
      direction: dir,
      source: 'block',
    };
  }
  return missHit();

  function missHit(): RaycastHit {
    return {
      block: null,
      entity: null,
      position: origin,
      origin,
      direction: dir,
      source: 'miss',
    };
  }
}

function nearestEntityAlongRay(
  bot: Bot,
  origin: Vec3,
  dir: Vec3,
  maxDist: number,
  radius: number,
  excludePlayerName: string
): Entity | null {
  let best: { e: Entity; d: number } | null = null;
  for (const e of Object.values(bot.entities)) {
    if (!e.position) continue;
    if (e.username === excludePlayerName) continue; // don't return the asker
    if (e.id === bot.entity?.id) continue;          // don't return ourselves

    // Project (e.pos - origin) onto dir; reject if outside [0, maxDist].
    const rel = e.position.minus(origin);
    const t = rel.dot(dir);
    if (t < 0 || t > maxDist) continue;

    // Perpendicular distance from ray to entity.
    const closest = origin.plus(dir.scaled(t));
    const perp = e.position.distanceTo(closest);
    if (perp > radius) continue;

    if (!best || t < best.d) best = { e, d: t };
  }
  return best?.e ?? null;
}
