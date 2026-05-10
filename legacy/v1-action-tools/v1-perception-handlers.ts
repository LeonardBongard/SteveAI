// Perception tool handlers — what the LLM can READ about the world.
// Per docs/COMPANION_V1_DIRECTION.md §3.5: minimal world state by default;
// the LLM tool-calls these for more when needed.
//
// All handlers are read-only and side-effect-free.

import type { Bot } from 'mineflayer';
import { resolveSpatialReference } from '../grounding/reference.js';
import type { ResolvedReference } from '../grounding/reference.js';
import { confirmAndAct } from '../grounding/confirm.js';
import { conversation } from '../memory/conversation.js';
import { episodic } from '../memory/episodic.js';
import { playbook } from '../memory/playbook.js';
import { logs } from '../log.js';
import type { ToolArgs } from '../llm/tools.js';

export interface PerceptionContext {
  bot: Bot;
  speakerName: string;
}

// --- world introspection ---

export async function getPlayerLocation(
  args: ToolArgs<'getPlayerLocation'>,
  { bot }: PerceptionContext
): Promise<string> {
  if (!args.playerName) {
    const p = bot.entity.position;
    return `bot at (${rnd(p.x)}, ${rnd(p.y)}, ${rnd(p.z)})`;
  }
  const e = bot.players[args.playerName]?.entity;
  if (!e) return `player ${args.playerName} not visible`;
  return `${args.playerName} at (${rnd(e.position.x)}, ${rnd(e.position.y)}, ${rnd(e.position.z)})`;
}

export async function getInventory(
  _args: ToolArgs<'getInventory'>,
  { bot }: PerceptionContext
): Promise<string> {
  const items = bot.inventory.items();
  if (items.length === 0) return 'inventory empty';
  const summary = items
    .map((i) => `${i.count}x ${i.name}`)
    .slice(0, 30)
    .join(', ');
  return `inventory: ${summary}${items.length > 30 ? ' …' : ''}`;
}

export async function findNearestBlock(
  args: ToolArgs<'findNearestBlock'>,
  { bot }: PerceptionContext
): Promise<string> {
  const max = args.maxDistance ?? 64;
  const block = bot.registry.blocksByName[args.blockType];
  if (!block) return `unknown block type: ${args.blockType}`;
  const found = bot.findBlock({ matching: block.id, maxDistance: max });
  if (!found) return `no ${args.blockType} within ${max} blocks`;
  return `nearest ${args.blockType} at (${found.position.x}, ${found.position.y}, ${found.position.z})`;
}

export async function getNearbyEntities(
  args: ToolArgs<'getNearbyEntities'>,
  { bot }: PerceptionContext
): Promise<string> {
  const max = args.maxDistance ?? 32;
  const limit = args.limit ?? 10;
  const here = bot.entity.position;
  const found: Array<{ name: string; dist: number; pos: string }> = [];
  for (const e of Object.values(bot.entities)) {
    if (e.id === bot.entity?.id) continue;
    const d = e.position.distanceTo(here);
    if (d > max) continue;
    found.push({
      name: e.username ?? e.name ?? `entity#${e.id}`,
      dist: d,
      pos: `(${rnd(e.position.x)}, ${rnd(e.position.y)}, ${rnd(e.position.z)})`,
    });
  }
  found.sort((a, b) => a.dist - b.dist);
  const top = found.slice(0, limit);
  if (top.length === 0) return `no entities within ${max} blocks`;
  return top.map((e) => `${e.name} ${rnd(e.dist)}m at ${e.pos}`).join('; ');
}

export async function getTimeOfDay(
  _args: ToolArgs<'getTimeOfDay'>,
  { bot }: PerceptionContext
): Promise<string> {
  const time = bot.time;
  const phase = time.isDay ? 'day' : 'night';
  return `${phase}; tick ${time.timeOfDay} (raw ${time.time})`;
}

// --- grounding ---

export async function resolveReference(
  args: ToolArgs<'resolveReference'>,
  ctx: PerceptionContext
): Promise<string> {
  const ref = await resolveSpatialReference(args.phrase, ctx);
  if (!ref) return `could not resolve "${args.phrase}" — ask the player to be more specific`;

  // Confirm in chat before the planner acts.
  const confirmation = confirmAndAct(ctx.bot, ref, { intent: args.verb ?? 'on it' });
  return formatResolved(ref, confirmation);
}

function formatResolved(ref: ResolvedReference, confirmation: string): string {
  const base = `resolved [${ref.kind}/${ref.source}]: ${ref.description}`;
  if (ref.position) {
    return `${base}; coords (${ref.position.x}, ${ref.position.y}, ${ref.position.z}); confirmed in chat: "${confirmation}"`;
  }
  return `${base}; confirmed in chat: "${confirmation}"`;
}

// --- memory tool wrappers (LLM-callable) ---

export async function pinFact(
  args: ToolArgs<'pinFact'>,
  _ctx: PerceptionContext
): Promise<string> {
  conversation.pinFact(args.key, args.value, args.context);
  return `pinned ${args.key} = ${args.value}`;
}

export async function recallPinnedFacts(
  _args: ToolArgs<'recallPinnedFacts'>,
  _ctx: PerceptionContext
): Promise<string> {
  const facts = conversation.pinnedFacts();
  if (facts.length === 0) return 'no pinned facts';
  return facts.map((f) => `${f.key}=${f.value}`).join('; ');
}

export async function recordEpisodicEvent(
  args: ToolArgs<'recordEpisodicEvent'>,
  { bot }: PerceptionContext
): Promise<string> {
  const recorded = episodic.recordEvent({
    event: args.event,
    x: args.x,
    y: args.y,
    z: args.z,
    dimension: args.dimension ?? bot.game.dimension ?? 'overworld',
    context: args.context ?? '',
  });
  return `recorded episodic ${recorded.event} at (${recorded.x}, ${recorded.y}, ${recorded.z})`;
}

export async function recallEpisodes(
  args: ToolArgs<'recallEpisodes'>,
  _ctx: PerceptionContext
): Promise<string> {
  const events = episodic.recent({
    ...(args.event ? { event: args.event } : {}),
    limit: args.limit ?? 5,
  });
  if (events.length === 0) return 'no episodic memories';
  return events
    .map((e) => `${e.event} at (${e.x}, ${e.y}, ${e.z}) [${e.ts}]: ${e.context}`)
    .join('; ');
}

export async function searchPlaybook(
  args: ToolArgs<'searchPlaybook'>,
  _ctx: PerceptionContext
): Promise<string> {
  const recipes = await playbook.search(args.query, args.k ?? 3);
  if (recipes.length === 0) return 'no matching recipes';
  return recipes
    .map((r) => `${r.name} (used ${r.successCount}x): ${r.description}`)
    .join('; ');
}

// --- utils ---

function rnd(n: number): number {
  return Math.round(n * 10) / 10;
}

logs.ground.debug(
  {
    handlers: [
      'getPlayerLocation',
      'getInventory',
      'findNearestBlock',
      'getNearbyEntities',
      'getTimeOfDay',
      'resolveReference',
      'pinFact',
      'recallPinnedFacts',
      'recordEpisodicEvent',
      'recallEpisodes',
      'searchPlaybook',
    ],
  },
  'perception handlers loaded'
);
