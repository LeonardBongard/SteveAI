// Spatial reference resolution — broader than raycast.
//
// Per docs/COMPANION_V1_DIRECTION.md §3.4 (the table): "that / over there",
// "the closest tree", "behind me", "this thing in my hand", "my house",
// "the cave from yesterday" — all resolvable against Mineflayer state +
// memory, none requiring vision.
//
// The LLM calls `resolveSpatialReference` with a free-form phrase; we try
// the cheap resolvers in order and return the first concrete (block or
// entity or coordinate) match. The bot is then expected to confirm the
// resolution in chat (grounding/confirm.ts) before acting on it.

import type { Bot } from 'mineflayer';
import type { Block } from 'prismarine-block';
import { Vec3 } from 'vec3';
import { raycastFromPlayer } from './raycast.js';
import { episodic } from '../memory/episodic.js';
import { conversation } from '../memory/conversation.js';
import { logs } from '../log.js';

export interface ResolvedReference {
  kind: 'block' | 'entity' | 'coordinate' | 'inventory_item';
  description: string;            // human-readable for confirm-back
  position?: { x: number; y: number; z: number; dimension?: string };
  blockType?: string;             // for kind === 'block'
  entityId?: number;              // for kind === 'entity'
  entityName?: string;
  itemName?: string;              // for kind === 'inventory_item'
  source: string;                 // which resolver matched ('raycast', 'nearest:iron_ore', etc.)
}

export interface ResolveContext {
  bot: Bot;
  speakerName: string;            // the player who said it
}

const DEICTIC_PATTERNS = [/\bthat\b/i, /\bthis\b/i, /\bover there\b/i, /\bwhat i'?m looking at\b/i];

/**
 * Resolve a spatial-reference phrase. Returns null if no resolver matches —
 * the planner should treat that as "ask the player to be more specific" rather
 * than guessing.
 */
export async function resolveSpatialReference(
  phrase: string,
  ctx: ResolveContext
): Promise<ResolvedReference | null> {
  const lc = phrase.toLowerCase().trim();
  logs.ground.debug({ phrase: lc }, 'resolveSpatialReference');

  // 1. Deixis — raycast from the player's eye.
  if (DEICTIC_PATTERNS.some((re) => re.test(lc))) {
    const hit = raycastFromPlayer(ctx.bot, ctx.speakerName);
    if (hit.source === 'block' && hit.block) {
      return blockRef(hit.block, 'raycast');
    }
    if (hit.source === 'entity' && hit.entity) {
      const name = hit.entity.username ?? hit.entity.name ?? null;
      return {
        kind: 'entity',
        description: hit.entity.username
          ? `the player ${hit.entity.username}`
          : `${hit.entity.name ?? 'an entity'} at (${fmt(hit.entity.position)})`,
        position: posAt(hit.entity.position),
        entityId: hit.entity.id,
        ...(name ? { entityName: name } : {}),
        source: 'raycast',
      };
    }
    // fall through if raycast missed; maybe one of the other resolvers fires
  }

  // 2. "behind me" / "in front of me" / "<n> blocks <direction>" — relative offsets.
  const offset = parseRelativeOffset(lc, ctx);
  if (offset) return offset;

  // 3. "this thing in my hand" / "what I'm holding".
  if (/\b(in my hand|holding|i'?m holding)\b/.test(lc)) {
    const item = ctx.bot.players[ctx.speakerName]?.entity?.heldItem;
    if (item) {
      return {
        kind: 'inventory_item',
        description: `your held item (${item.name})`,
        itemName: item.name,
        source: 'held_item',
      };
    }
  }

  // 4. "the closest <X>" / "the nearest <X>" / "the <X> nearby".
  const nearestMatch = lc.match(/\b(?:closest|nearest|nearby)\s+([a-z_]+)\b/);
  if (nearestMatch) {
    const target = nearestMatch[1];
    if (target) {
      const block = findNearestBlockByName(ctx.bot, target);
      if (block) return blockRef(block, `nearest:${target}`);
    }
  }

  // 5. Pinned-fact recall: "my house", "my base".
  const pin = matchPinnedFact(lc);
  if (pin) return pin;

  // 6. Episodic recall: "the cave you found", "where I died".
  const epi = matchEpisodicEvent(lc);
  if (epi) return epi;

  return null;
}

// --- internal resolvers ---

function blockRef(block: Block, source: string): ResolvedReference {
  return {
    kind: 'block',
    description: `${prettyBlockName(block.name)} at (${block.position.x}, ${block.position.y}, ${block.position.z})`,
    position: { x: block.position.x, y: block.position.y, z: block.position.z },
    blockType: block.name,
    source,
  };
}

function findNearestBlockByName(bot: Bot, name: string): Block | null {
  // Mineflayer's typed registry lookup.
  const reg = bot.registry;
  const blockType = reg.blocksByName[name];
  if (!blockType) return null;

  return (
    bot.findBlock({
      matching: blockType.id,
      maxDistance: 64,
    }) ?? null
  );
}

function parseRelativeOffset(lc: string, ctx: ResolveContext): ResolvedReference | null {
  const player = ctx.bot.players[ctx.speakerName]?.entity;
  if (!player) return null;

  // Defaults: 5 blocks in the named direction.
  const distMatch = lc.match(/(\d+)\s+blocks?\s+(north|south|east|west|up|down)/);
  if (distMatch?.[1] && distMatch[2]) {
    const n = Number(distMatch[1]);
    const dir = distMatch[2];
    const target = applyDirection(player.position, dir as DirName, n);
    return {
      kind: 'coordinate',
      description: `${n} block(s) ${dir} of you, at (${fmt(target)})`,
      position: posAt(target),
      source: 'relative_named',
    };
  }
  if (/\bbehind me\b/.test(lc)) {
    const back = backwardFromYaw(player.position, player.yaw, 3);
    return {
      kind: 'coordinate',
      description: `behind you, at (${fmt(back)})`,
      position: posAt(back),
      source: 'relative_behind',
    };
  }
  return null;
}

function matchPinnedFact(lc: string): ResolvedReference | null {
  // Naive but effective: substring match on pinned-fact keys / values.
  const facts = conversation.pinnedFacts();
  for (const f of facts) {
    if (!f.key.startsWith('location:')) continue;
    const label = f.key.slice('location:'.length).replace(/_/g, ' ');
    if (lc.includes(label) || lc.includes(`my ${label}`)) {
      const xyz = parseXyzValue(f.value);
      if (xyz) {
        return {
          kind: 'coordinate',
          description: `${label} (your pinned location at ${fmt(new Vec3(xyz.x, xyz.y, xyz.z))})`,
          position: xyz,
          source: `pinned:${f.key}`,
        };
      }
    }
  }
  return null;
}

function matchEpisodicEvent(lc: string): ResolvedReference | null {
  const cases: Array<{ patterns: RegExp[]; eventTag: string; label: string }> = [
    { patterns: [/\bcave\b/], eventTag: 'found_cave', label: 'cave' },
    { patterns: [/\bwhere i died\b/, /\bdeath\b/], eventTag: 'died', label: 'where you died' },
    { patterns: [/\biron\b/], eventTag: 'mined_iron', label: 'iron ore site' },
  ];
  for (const c of cases) {
    if (!c.patterns.some((re) => re.test(lc))) continue;
    const ev = episodic.lastOf(c.eventTag);
    if (ev) {
      return {
        kind: 'coordinate',
        description: `${c.label} (last seen at (${ev.x}, ${ev.y}, ${ev.z}) in ${ev.dimension})`,
        position: { x: ev.x, y: ev.y, z: ev.z, dimension: ev.dimension },
        source: `episodic:${c.eventTag}`,
      };
    }
  }
  return null;
}

// --- small helpers ---

type DirName = 'north' | 'south' | 'east' | 'west' | 'up' | 'down';

function applyDirection(pos: Vec3, dir: DirName, n: number): Vec3 {
  switch (dir) {
    case 'north':
      return pos.offset(0, 0, -n); // -Z = north in Minecraft
    case 'south':
      return pos.offset(0, 0, n);
    case 'east':
      return pos.offset(n, 0, 0);
    case 'west':
      return pos.offset(-n, 0, 0);
    case 'up':
      return pos.offset(0, n, 0);
    case 'down':
      return pos.offset(0, -n, 0);
  }
}

function backwardFromYaw(pos: Vec3, yaw: number, n: number): Vec3 {
  // Forward (Mineflayer convention) is -sin(yaw), 0, -cos(yaw); backward = -forward.
  return pos.offset(Math.sin(yaw) * n, 0, Math.cos(yaw) * n);
}

function fmt(v: Vec3): string {
  return `${Math.round(v.x)}, ${Math.round(v.y)}, ${Math.round(v.z)}`;
}

function posAt(v: Vec3): { x: number; y: number; z: number } {
  return { x: Math.round(v.x), y: Math.round(v.y), z: Math.round(v.z) };
}

function prettyBlockName(name: string): string {
  return name.replace(/_/g, ' ');
}

function parseXyzValue(value: string): { x: number; y: number; z: number } | null {
  // Accept "[x, y, z]" or "x,y,z".
  const cleaned = value.replace(/[\[\]]/g, '').trim();
  const parts = cleaned.split(',').map((s) => Number(s.trim()));
  if (parts.length !== 3) return null;
  if (parts.some((n) => !Number.isFinite(n))) return null;
  const [x, y, z] = parts as [number, number, number];
  return { x, y, z };
}
