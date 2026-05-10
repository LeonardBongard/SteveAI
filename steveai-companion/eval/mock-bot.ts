// Mock Bot for the eval harness.
//
// Implements just enough of the mineflayer.Bot surface that the planner +
// tool handlers can run end-to-end without a Minecraft server. The real
// LLM (Ollama) and the real memory store ARE used — only the world / wire
// layer is mocked.
//
// The mock world is a Map<"x,y,z", blockName>. Pathfinder is teleport
// (instant arrival; we don't simulate pathing). Dig is "remove from world".
// Place is "insert into world". Inventory is a simple array.
//
// What the planner / handlers actually use from the Bot:
//   bot.username, bot.entity.position, bot.players, bot.entities,
//   bot.inventory.items(), bot.heldItem, bot.chat(),
//   bot.findBlock(), bot.dig(), bot.placeBlock(), bot.equip(),
//   bot.pathfinder.{setMovements, goto, setGoal, isMoving, stop, movements},
//   bot.clearControlStates(), bot.world.raycast(), bot.registry.blocksByName,
//   bot.time, bot.game.dimension, bot.blockAt()
//
// We expose ALL of these. Everything else is a no-op or undefined.

import type { Bot } from 'mineflayer';
import type { Block } from 'prismarine-block';
import type { Entity } from 'prismarine-entity';
import { Vec3 } from 'vec3';
import { EventEmitter } from 'node:events';
import type { MockPlayer, MockSetup, MockWorldBlock } from './types.js';

// Minimal block catalog. Keep IDs stable & arbitrary; the planner doesn't care
// about real Minecraft IDs, just that they round-trip through findBlock.
const BLOCK_CATALOG: Record<string, { id: number; harvestTools?: Record<number, true> }> = {
  air: { id: 0 },
  dirt: { id: 1 },
  grass_block: { id: 2 },
  stone: { id: 3, harvestTools: { 100: true } },        // requires wooden_pickaxe (id 100)
  cobblestone: { id: 4 },
  oak_log: { id: 5 },
  oak_planks: { id: 6 },
  iron_ore: { id: 7, harvestTools: { 101: true } },     // requires stone_pickaxe (id 101)
  diamond_ore: { id: 8, harvestTools: { 102: true } },  // requires iron_pickaxe (id 102)
  iron_ingot: { id: 50 },
  diamond: { id: 51 },
  wooden_pickaxe: { id: 100 },
  stone_pickaxe: { id: 101 },
  iron_pickaxe: { id: 102 },
  diamond_pickaxe: { id: 103 },
  crafting_table: { id: 200 },
  furnace: { id: 201 },
};

function blockKey(x: number, y: number, z: number): string {
  return `${Math.floor(x)},${Math.floor(y)},${Math.floor(z)}`;
}

function blockToPrismarine(name: string, x: number, y: number, z: number): Block {
  const meta = BLOCK_CATALOG[name] ?? { id: -1 };
  // We construct a minimal stub; cast through unknown so we don't have to
  // implement all of prismarine-block's methods.
  return {
    name,
    type: meta.id,
    position: new Vec3(x, y, z),
    boundingBox: 'block',
    transparent: name === 'air',
    diggable: name !== 'air',
    metadata: 0,
    light: 0,
    skyLight: 0,
    biome: { name: 'plains' },
    digTime: () => 1000,
  } as unknown as Block;
}

export interface MockBotRecord {
  toolEvents: Array<{ tool: string; args: unknown; ts: string }>;
  chatLines: string[];
}

export class MockBot extends EventEmitter {
  username: string;
  entity: { position: Vec3; id: number; yaw: number; pitch: number };
  players: Record<string, { entity: Entity }> = {};
  entities: Record<number, Entity> = {};
  heldItem: { name: string; type: number; count: number } | null = null;
  game = { dimension: 'overworld' };
  time = { isDay: true, timeOfDay: 6000, time: 6000 };

  /** Non-Bot helpers used internally. */
  record: MockBotRecord = { toolEvents: [], chatLines: [] };
  world: { raycast: (o: Vec3, d: Vec3, r: number) => Block | null };
  pathfinder: MockPathfinder;
  inventory: { items: () => Array<{ name: string; type: number; count: number }> };
  registry: { blocksByName: Record<string, { id: number; harvestTools?: Record<number, true> }> };

  private blocks = new Map<string, string>();
  private nextEntityId = 100;
  private inventoryItems: Array<{ name: string; type: number; count: number }> = [];

  constructor(username: string, setup: MockSetup) {
    super();
    this.username = username;
    this.entity = {
      id: 1,
      position: new Vec3(setup.bot.x, setup.bot.y, setup.bot.z),
      yaw: 0,
      pitch: 0,
    };

    // Players
    for (const p of setup.players ?? []) {
      this.addPlayer(p);
    }

    // Static world
    for (const b of setup.blocks ?? []) {
      this.setBlockMock(b);
    }

    // Inventory
    for (const i of setup.inventory ?? []) {
      const meta = BLOCK_CATALOG[i.name];
      this.inventoryItems.push({ name: i.name, type: meta?.id ?? -1, count: i.count });
    }

    this.world = {
      raycast: (o: Vec3, d: Vec3, range: number) => this.raycast(o, d, range),
    };
    this.pathfinder = new MockPathfinder(this);
    this.inventory = { items: () => this.inventoryItems.slice() };
    this.registry = { blocksByName: BLOCK_CATALOG };
  }

  // --- Bot API the handlers/planner use ---

  chat(message: string): void {
    this.record.chatLines.push(message);
    this.record.toolEvents.push({ tool: '_chat', args: { message }, ts: new Date().toISOString() });
  }

  blockAt(pos: Vec3): Block | null {
    const key = blockKey(pos.x, pos.y, pos.z);
    const name = this.blocks.get(key) ?? 'air';
    return blockToPrismarine(name, Math.floor(pos.x), Math.floor(pos.y), Math.floor(pos.z));
  }

  findBlock(opts: { matching: number; maxDistance: number }): Block | null {
    const targetName = Object.entries(BLOCK_CATALOG).find(([, v]) => v.id === opts.matching)?.[0];
    if (!targetName) return null;
    const here = this.entity.position;
    let best: { name: string; x: number; y: number; z: number; d: number } | null = null;
    for (const [k, name] of this.blocks) {
      if (name !== targetName) continue;
      const parts = k.split(',').map(Number);
      const x = parts[0] ?? 0;
      const y = parts[1] ?? 0;
      const z = parts[2] ?? 0;
      const d = Math.hypot(x - here.x, y - here.y, z - here.z);
      if (d > opts.maxDistance) continue;
      if (!best || d < best.d) best = { name, x, y, z, d };
    }
    if (!best) return null;
    return blockToPrismarine(best.name, best.x, best.y, best.z);
  }

  async dig(block: Block): Promise<void> {
    this.record.toolEvents.push({
      tool: '_dig',
      args: { x: block.position.x, y: block.position.y, z: block.position.z, name: block.name },
      ts: new Date().toISOString(),
    });
    this.blocks.delete(blockKey(block.position.x, block.position.y, block.position.z));
    // Add to inventory (one of whatever we mined).
    this.addInventory(block.name, 1);
  }

  async placeBlock(refBlock: Block, faceVec: Vec3): Promise<void> {
    const x = refBlock.position.x + faceVec.x;
    const y = refBlock.position.y + faceVec.y;
    const z = refBlock.position.z + faceVec.z;
    const item = this.heldItem;
    if (!item) throw new Error('nothing held');
    this.record.toolEvents.push({
      tool: '_placeBlock',
      args: { x, y, z, name: item.name },
      ts: new Date().toISOString(),
    });
    this.blocks.set(blockKey(x, y, z), item.name);
    this.removeInventory(item.name, 1);
  }

  async equip(item: { name: string; type: number; count: number }, dest: string): Promise<void> {
    this.record.toolEvents.push({
      tool: '_equip',
      args: { name: item.name, dest },
      ts: new Date().toISOString(),
    });
    if (dest === 'hand') this.heldItem = item;
  }

  clearControlStates(): void {
    /* no-op */
  }

  // --- mock world manipulation (used by tests) ---

  private setBlockMock(b: MockWorldBlock): void {
    this.blocks.set(blockKey(b.x, b.y, b.z), b.name);
  }

  private addPlayer(p: MockPlayer): void {
    const id = this.nextEntityId++;
    const heldItem = p.heldItem
      ? { name: p.heldItem, type: BLOCK_CATALOG[p.heldItem]?.id ?? -1, count: 1 }
      : null;
    const entity = {
      id,
      username: p.username,
      name: 'player',
      position: new Vec3(p.x, p.y, p.z),
      yaw: p.yaw ?? 0,
      pitch: p.pitch ?? 0,
      heldItem,
    } as unknown as Entity;
    this.players[p.username] = { entity };
    this.entities[id] = entity;
  }

  private raycast(origin: Vec3, dir: Vec3, range: number): Block | null {
    // Step 0.1 along the ray; sufficient for our 64-block range. Stop on
    // the first non-air block.
    const step = 0.1;
    const steps = Math.ceil(range / step);
    for (let i = 1; i <= steps; i++) {
      const t = i * step;
      const x = origin.x + dir.x * t;
      const y = origin.y + dir.y * t;
      const z = origin.z + dir.z * t;
      const name = this.blocks.get(blockKey(x, y, z));
      if (name && name !== 'air') {
        return blockToPrismarine(name, Math.floor(x), Math.floor(y), Math.floor(z));
      }
    }
    return null;
  }

  private addInventory(name: string, count: number): void {
    const existing = this.inventoryItems.find((i) => i.name === name);
    if (existing) {
      existing.count += count;
      return;
    }
    const meta = BLOCK_CATALOG[name];
    this.inventoryItems.push({ name, type: meta?.id ?? -1, count });
  }

  private removeInventory(name: string, count: number): void {
    const existing = this.inventoryItems.find((i) => i.name === name);
    if (!existing) return;
    existing.count -= count;
    if (existing.count <= 0) {
      this.inventoryItems = this.inventoryItems.filter((i) => i !== existing);
      if (this.heldItem?.name === name) this.heldItem = null;
    }
  }
}

class MockPathfinder {
  movements: object = {};
  private goal: object | null = null;
  private bot: MockBot;
  constructor(bot: MockBot) {
    this.bot = bot;
  }
  setMovements(_m: unknown): void {
    /* no-op */
  }
  async goto(goal: { x?: number; y?: number; z?: number }): Promise<void> {
    this.bot.record.toolEvents.push({
      tool: '_pathfinder_goto',
      args: { x: goal.x, y: goal.y, z: goal.z },
      ts: new Date().toISOString(),
    });
    if (goal.x !== undefined && goal.y !== undefined && goal.z !== undefined) {
      this.bot.entity.position = new Vec3(goal.x, goal.y + 1, goal.z);
    }
  }
  setGoal(goal: object, _persistent?: boolean): void {
    this.goal = goal;
    this.bot.record.toolEvents.push({
      tool: '_pathfinder_setGoal',
      args: { goal: 'set' },
      ts: new Date().toISOString(),
    });
  }
  isMoving(): boolean {
    return this.goal !== null;
  }
  stop(): void {
    this.goal = null;
  }
}

/** Cast a MockBot to mineflayer.Bot through `unknown`. The handlers don't
 *  use anything we haven't implemented; if they do, the runtime will yell. */
export function asBot(mock: MockBot): Bot {
  return mock as unknown as Bot;
}
