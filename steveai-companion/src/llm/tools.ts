// Tool schemas exposed to gpt-oss. Defined with Zod, converted to JSON Schema
// for Ollama's tool-calling API (OpenAI-compatible shape).
//
// Per docs/COMPANION_V1_DIRECTION.md §3.7 #10: gpt-oss is post-trained for
// the OpenAI tool-calling format; we use it natively. Schemas are kept terse —
// every word here gets KV-cached and re-sent per turn (§3.7 #9).
//
// Handlers live in actions/handlers.ts and perception/handlers.ts. The planner
// (planner.ts) wires schemas to handlers.

import { z } from 'zod';
import { zodToJsonSchema } from 'zod-to-json-schema';

// ============================================================================
// Action tools (cause changes in the world)
// ============================================================================

const sayToPlayerSchema = z
  .object({
    message: z.string().min(1).max(256),
  })
  .describe('Send a chat message visible to the player. Keep it short.');

const gotoSchema = z
  .object({
    x: z.number().int(),
    y: z.number().int(),
    z: z.number().int(),
    range: z.number().int().min(0).max(10).optional(),
  })
  .describe('Pathfind to a coordinate. range = how close to get (default 1).');

const mineBlockSchema = z
  .object({
    x: z.number().int(),
    y: z.number().int(),
    z: z.number().int(),
  })
  .describe('Mine the block at the given coordinate. Bot will path within reach if needed.');

const placeBlockSchema = z
  .object({
    x: z.number().int(),
    y: z.number().int(),
    z: z.number().int(),
    itemName: z.string(),
  })
  .describe('Place a block from inventory. There must be a non-air block directly below.');

const equipItemSchema = z
  .object({
    itemName: z.string(),
    destination: z.enum(['hand', 'off-hand', 'head', 'torso', 'legs', 'feet']).optional(),
  })
  .describe('Equip an item from inventory. Default destination is "hand".');

const followPlayerSchema = z
  .object({
    playerName: z.string(),
    range: z.number().int().min(1).max(10).optional(),
  })
  .describe('Continuously follow a player at a given range (default 3 blocks). Use stop to cancel.');

const stopSchema = z
  .object({})
  .describe('Stop any ongoing pathfinding / following.');

// ============================================================================
// Perception tools (read-only world / memory)
// ============================================================================

const getPlayerLocationSchema = z
  .object({
    playerName: z.string().optional(),
  })
  .describe('Get the bot\'s position, or a named player\'s position if given.');

const getInventorySchema = z
  .object({})
  .describe('Return the current inventory.');

const findNearestBlockSchema = z
  .object({
    blockType: z.string(),
    maxDistance: z.number().int().min(1).max(256).optional(),
  })
  .describe('Find the nearest block of a given type. Returns coordinates or "not found".');

const getNearbyEntitiesSchema = z
  .object({
    maxDistance: z.number().int().min(1).max(128).optional(),
    limit: z.number().int().min(1).max(20).optional(),
  })
  .describe('List entities (mobs, players, items) near the bot, sorted by distance.');

const getTimeOfDaySchema = z
  .object({})
  .describe('Return whether it is day or night and the current Minecraft tick.');

const resolveReferenceSchema = z
  .object({
    phrase: z.string().min(1),
    verb: z.string().optional(),
  })
  .describe('Resolve a spatial reference like "that ore", "the closest tree", "my house", "behind me", "where I died". Echoes the resolved target back to the player in chat before returning.');

// ============================================================================
// Memory tools
// ============================================================================

const pinFactSchema = z
  .object({
    key: z.string().min(1),
    value: z.string().min(1),
    context: z.string().optional(),
  })
  .describe('Store a durable fact about the player or world. Keys: preference:<thing>, location:<name>, dislike:<thing>, etc.');

const recallPinnedFactsSchema = z
  .object({})
  .describe('List all pinned facts (player preferences, named locations, etc.).');

const recordEpisodicEventSchema = z
  .object({
    event: z.string().min(1),
    x: z.number().int(),
    y: z.number().int(),
    z: z.number().int(),
    dimension: z.string().optional(),
    context: z.string().optional(),
  })
  .describe('Record a notable event with coordinates: e.g. "found_cave", "mined_iron", "died". Use sparingly — only events worth recalling later.');

const recallEpisodesSchema = z
  .object({
    event: z.string().optional(),
    limit: z.number().int().min(1).max(20).optional(),
  })
  .describe('Recall recent episodic events, optionally filtered by event tag.');

const searchPlaybookSchema = z
  .object({
    query: z.string().min(1),
    k: z.number().int().min(1).max(10).optional(),
  })
  .describe('Search past successful task recipes for similar work. Returns recipe names + descriptions.');

// ============================================================================
// Registry
// ============================================================================

export const TOOLS = {
  // actions
  sayToPlayer: sayToPlayerSchema,
  goto: gotoSchema,
  mineBlock: mineBlockSchema,
  placeBlock: placeBlockSchema,
  equipItem: equipItemSchema,
  followPlayer: followPlayerSchema,
  stop: stopSchema,
  // perception / world
  getPlayerLocation: getPlayerLocationSchema,
  getInventory: getInventorySchema,
  findNearestBlock: findNearestBlockSchema,
  getNearbyEntities: getNearbyEntitiesSchema,
  getTimeOfDay: getTimeOfDaySchema,
  resolveReference: resolveReferenceSchema,
  // memory
  pinFact: pinFactSchema,
  recallPinnedFacts: recallPinnedFactsSchema,
  recordEpisodicEvent: recordEpisodicEventSchema,
  recallEpisodes: recallEpisodesSchema,
  searchPlaybook: searchPlaybookSchema,
} as const;

export type ToolName = keyof typeof TOOLS;
export type ToolArgs<N extends ToolName> = z.infer<(typeof TOOLS)[N]>;

export interface OllamaTool {
  type: 'function';
  function: {
    name: string;
    description: string;
    parameters: ReturnType<typeof zodToJsonSchema>;
  };
}

export function buildOllamaTools(): OllamaTool[] {
  return Object.entries(TOOLS).map(([name, schema]) => ({
    type: 'function',
    function: {
      name,
      description: schema.description ?? '',
      parameters: zodToJsonSchema(schema, { target: 'openApi3' }),
    },
  }));
}

export function parseToolArgs<N extends ToolName>(name: N, raw: unknown): ToolArgs<N> {
  const schema = TOOLS[name];
  return schema.parse(raw) as ToolArgs<N>;
}

export function isKnownTool(name: string): name is ToolName {
  return Object.prototype.hasOwnProperty.call(TOOLS, name);
}
