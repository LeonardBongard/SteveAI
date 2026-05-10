// Action tool handlers — what the LLM can DO in the world.
//
// Each handler returns an "observation" string: a short, factual line
// describing what happened. The observation flows back into the LLM's
// next turn (per docs/COMPANION_V1_DIRECTION.md §3.5 control loop).
// Format convention: succinct past-tense facts. "moved to (12, 64, 5)",
// "mined 1 oak_log; inventory now has 17", "failed: no diamond pickaxe".

import type { Bot } from 'mineflayer';
import { Vec3 } from 'vec3';
import pathfinderPkg from 'mineflayer-pathfinder';
const { goals, Movements } = pathfinderPkg;
import { logs } from '../log.js';
import type { ToolArgs } from '../llm/tools.js';

const GOTO_TIMEOUT_MS = 30_000;
const DIG_TIMEOUT_MS = 30_000;

export interface ActionContext {
  bot: Bot;
}

// --- safeSay shared util (also used by the planner / confirm flow) ---
const MAX_CHAT_LEN = 240;
export function safeSay(bot: Bot, text: string): void {
  const trimmed = text.trim();
  if (trimmed.length === 0) return;
  for (let i = 0; i < trimmed.length; i += MAX_CHAT_LEN) {
    bot.chat(trimmed.slice(i, i + MAX_CHAT_LEN));
  }
}

// --- handlers ---

export async function actSayToPlayer(
  args: ToolArgs<'sayToPlayer'>,
  { bot }: ActionContext
): Promise<string> {
  safeSay(bot, args.message);
  return `said: ${args.message.slice(0, 80)}${args.message.length > 80 ? '…' : ''}`;
}

export async function actGoto(
  args: ToolArgs<'goto'>,
  { bot }: ActionContext
): Promise<string> {
  const range = args.range ?? 1;
  const target = new Vec3(args.x, args.y, args.z);
  ensurePathfinder(bot);

  return await runWithTimeout(GOTO_TIMEOUT_MS, async () => {
    await bot.pathfinder.goto(new goals.GoalNear(args.x, args.y, args.z, range));
    const here = bot.entity.position;
    const dist = here.distanceTo(target);
    return `arrived near (${args.x}, ${args.y}, ${args.z}); now at (${rnd(here.x)}, ${rnd(here.y)}, ${rnd(here.z)}), ${rnd(dist)}m from target`;
  }, `pathfinder timeout going to (${args.x}, ${args.y}, ${args.z})`);
}

export async function actMineBlock(
  args: ToolArgs<'mineBlock'>,
  { bot }: ActionContext
): Promise<string> {
  const pos = new Vec3(args.x, args.y, args.z);
  const block = bot.blockAt(pos);
  if (!block || block.name === 'air') {
    return `no block at (${args.x}, ${args.y}, ${args.z}) — already air`;
  }

  // Step within reach if needed.
  const dist = bot.entity.position.distanceTo(pos);
  if (dist > 4.5) {
    ensurePathfinder(bot);
    try {
      await runWithTimeout(GOTO_TIMEOUT_MS, async () => {
        await bot.pathfinder.goto(new goals.GoalLookAtBlock(pos, bot.world));
        return '';
      }, `path-to-block timeout`);
    } catch (err) {
      return `failed to reach (${args.x}, ${args.y}, ${args.z}): ${(err as Error).message}`;
    }
  }

  const harvestable = canHarvest(bot, block.name);
  if (!harvestable.ok) return `cannot mine ${block.name}: ${harvestable.reason}`;

  try {
    const result = await runWithTimeout(DIG_TIMEOUT_MS, async () => {
      await bot.dig(block);
      return `mined ${block.name} at (${args.x}, ${args.y}, ${args.z})`;
    }, `dig timeout on ${block.name}`);
    return result;
  } catch (err) {
    return `dig failed: ${(err as Error).message}`;
  }
}

export async function actPlaceBlock(
  args: ToolArgs<'placeBlock'>,
  { bot }: ActionContext
): Promise<string> {
  const item = bot.inventory.items().find((i) => i.name === args.itemName);
  if (!item) return `failed: no ${args.itemName} in inventory`;

  try {
    await bot.equip(item, 'hand');
  } catch (err) {
    return `failed to equip ${args.itemName}: ${(err as Error).message}`;
  }

  // Place on top of the block at (x, y-1, z) by convention. The LLM should
  // pass the *target* position; we infer the reference block as the one below.
  const refPos = new Vec3(args.x, args.y - 1, args.z);
  const ref = bot.blockAt(refPos);
  if (!ref || ref.name === 'air') {
    return `failed: no surface to place against under (${args.x}, ${args.y}, ${args.z})`;
  }

  try {
    await bot.placeBlock(ref, new Vec3(0, 1, 0));
    return `placed ${args.itemName} at (${args.x}, ${args.y}, ${args.z})`;
  } catch (err) {
    return `place failed: ${(err as Error).message}`;
  }
}

export async function actEquipItem(
  args: ToolArgs<'equipItem'>,
  { bot }: ActionContext
): Promise<string> {
  const dest = args.destination ?? 'hand';
  const item = bot.inventory.items().find((i) => i.name === args.itemName);
  if (!item) return `failed: no ${args.itemName} in inventory`;
  try {
    await bot.equip(item, dest);
    return `equipped ${args.itemName} to ${dest}`;
  } catch (err) {
    return `equip failed: ${(err as Error).message}`;
  }
}

export async function actFollowPlayer(
  args: ToolArgs<'followPlayer'>,
  { bot }: ActionContext
): Promise<string> {
  const range = args.range ?? 3;
  const target = bot.players[args.playerName]?.entity;
  if (!target) return `failed: don't see player ${args.playerName}`;
  ensurePathfinder(bot);
  bot.pathfinder.setGoal(new goals.GoalFollow(target, range), true);
  return `following ${args.playerName} at range ${range}`;
}

export async function actStop(
  _args: ToolArgs<'stop'>,
  { bot }: ActionContext
): Promise<string> {
  if (bot.pathfinder?.isMoving()) bot.pathfinder.stop();
  bot.clearControlStates();
  return 'stopped';
}

// --- internal utils ---

function ensurePathfinder(bot: Bot): void {
  // mineflayer-pathfinder is auto-attached at bot startup (bot.ts); this is a
  // double-check.
  if (!bot.pathfinder) {
    throw new Error('pathfinder plugin not loaded; call bot.loadPlugin(pathfinder) at startup');
  }
  if (!bot.pathfinder.movements) {
    bot.pathfinder.setMovements(new Movements(bot));
  }
}

interface HarvestCheck {
  ok: boolean;
  reason: string;
}
function canHarvest(bot: Bot, blockName: string): HarvestCheck {
  const block = bot.registry.blocksByName[blockName];
  if (!block) return { ok: false, reason: `unknown block ${blockName}` };
  const harvestTools = block.harvestTools;
  if (!harvestTools) return { ok: true, reason: '' };
  const heldId = bot.heldItem?.type;
  if (heldId !== undefined && harvestTools[heldId]) return { ok: true, reason: '' };

  // Try to find any usable tool in inventory.
  for (const item of bot.inventory.items()) {
    if (harvestTools[item.type]) {
      return { ok: true, reason: '' };
    }
  }
  return { ok: false, reason: 'no suitable tool in inventory' };
}

async function runWithTimeout<T>(
  ms: number,
  fn: () => Promise<T>,
  timeoutMessage: string
): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(timeoutMessage)), ms);
  });
  try {
    return await Promise.race([fn(), timeout]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function rnd(n: number): number {
  return Math.round(n * 10) / 10;
}

// Re-export with a default name suitable for log inspection.
logs.act.debug({ handlers: ['sayToPlayer', 'goto', 'mineBlock', 'placeBlock', 'equipItem', 'followPlayer', 'stop'] }, 'action handlers loaded');
