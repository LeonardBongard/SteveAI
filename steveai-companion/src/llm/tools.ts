// LLM tool schemas — v2.
//
// Per docs/COMPANION_V2_DIRECTION.md §3.2: ~15 META-LEVEL tools. None are
// pre-baked behaviors. Action capability comes from the LLM writing skills
// (writeSkill / invokeSkill / runOnce) that USE Mineflayer's API directly
// inside the sandbox.
//
// Tool surface:
//   I/O  : chat
//   skill: writeSkill, invokeSkill, searchSkill, runOnce, listSkills
//   know : lookupRecipe, lookupMob, lookupBlock, findRecipesContaining,
//          findMobsDropping
//   memory: pinFact, recallPinnedFacts, recordEpisodicEvent, recallEpisodes
//   ground: resolveReference  (kept — high-value grounding the LLM shouldn't
//                              re-implement in skill code)

import { z } from 'zod';
import { zodToJsonSchema } from 'zod-to-json-schema';

// ============================================================================
// I/O
// ============================================================================

const chatSchema = z
  .object({
    message: z.string().min(1).max(1024),
  })
  .describe('Send a chat message visible to the player. Keep it short — a sentence or two. The underlying Mineflayer safeSay splits at 240-char boundaries, so long messages render as multiple lines in-game.');

// ============================================================================
// Skill execution / management — the v2 core
// ============================================================================

const writeSkillSchema = z
  .object({
    name: z.string().min(1).max(60).describe('snake_case identifier; semantically descriptive (e.g. mine_one_oak_log, craft_fishing_rod). Used for retrieval; pick a clear name a weaker model could pick later.'),
    description: z.string().min(10).describe('One-sentence natural-language description of what the skill does. Specific enough that searchSkill returns it for similar requests later.'),
    code: z.string().min(1).describe('Body of an async function (bot, args) => { ... }. Available in scope: bot (mineflayer), Vec3, goals, Movements, console. Do NOT use require() — pre-injected globals only. Throw on failure.'),
  })
  .describe('Save an executable skill (JS) into the skill library. Persisted across sessions. After saving, INVOKE it to run.');

const invokeSkillSchema = z
  .object({
    name: z.string().min(1),
    args: z.record(z.unknown()).optional().describe('Optional arguments object passed to the skill as `args`. Defaults to {}.'),
  })
  .describe('Run a saved skill from the library by name. Returns success/failure + observation.');

const searchSkillSchema = z
  .object({
    query: z.string().min(1),
    k: z.number().int().min(1).max(8).optional(),
  })
  .describe('Search the skill library by description embedding. Use this BEFORE writing a new skill — there may already be one for this task.');

const runOnceSchema = z
  .object({
    code: z.string().min(1).describe('Async function body (bot, args) => { ... }. Same scope as writeSkill. Used for one-shot exploration / inspection. NOT saved.'),
  })
  .describe('Execute Mineflayer code once without saving it. Use for quick checks ("what blocks are nearby?"), not for actions you want to repeat.');

const listSkillsSchema = z
  .object({
    limit: z.number().int().min(1).max(50).optional(),
  })
  .describe('List recent / most-used skills in the library.');

// ============================================================================
// Knowledge RAG — minecraft-data lookups
// ============================================================================

const lookupRecipeSchema = z
  .object({
    item: z.string().min(1).describe('Item name, e.g. "fishing_rod" or "iron_pickaxe". Use lowercase snake_case.'),
  })
  .describe('Look up the EXACT crafting recipe(s) for an item. Use this BEFORE writing any craft_* skill — do NOT guess recipes.');

const lookupMobSchema = z
  .object({
    name: z.string().min(1).describe('Mob name, e.g. "spider", "zombie".'),
  })
  .describe('Look up a mob: drop table (with chances + counts), spawn conditions, basic facts.');

const lookupBlockSchema = z
  .object({
    name: z.string().min(1).describe('Block name, e.g. "iron_ore", "diamond_ore", "oak_log".'),
  })
  .describe('Look up a block: hardness, harvest tools, drops.');

const findRecipesContainingSchema = z
  .object({
    item: z.string().min(1).describe('Ingredient name, e.g. "string", "stick".'),
  })
  .describe('Reverse recipe lookup: which recipes use this item? Useful when the player names a material and you want to find what they could make.');

const findMobsDroppingSchema = z
  .object({
    item: z.string().min(1).describe('Item name, e.g. "string", "blaze_rod".'),
  })
  .describe('Reverse drop lookup: which mobs drop this item? Use this when the player asks for an item that is mob-derived (string from spider, leather from cow, etc.).');

// ============================================================================
// Grounding (kept from v1)
// ============================================================================

const resolveReferenceSchema = z
  .object({
    phrase: z.string().min(1),
    verb: z.string().optional(),
  })
  .describe('Resolve a spatial reference like "that ore", "the closest tree", "my house", "behind me", "where I died". Uses raycast / nearest / pinned-fact / episodic memory. Echoes the resolved target back to the player in chat before returning.');

// ============================================================================
// Memory (kept from v1)
// ============================================================================

const pinFactSchema = z
  .object({
    key: z.string().min(1).describe('e.g. preference:wood_type, location:home, dislike:birch.'),
    value: z.string().min(1),
    context: z.string().optional(),
  })
  .describe('Store a durable fact about the player or world. Persists across sessions.');

const recallPinnedFactsSchema = z
  .object({})
  .describe('List all pinned facts (player preferences, named locations, etc.).');

const recordEpisodicEventSchema = z
  .object({
    event: z.string().min(1).describe('Short tag, e.g. found_cave, mined_iron, died.'),
    x: z.number().int(),
    y: z.number().int(),
    z: z.number().int(),
    dimension: z.string().optional(),
    context: z.string().optional(),
  })
  .describe('Record a notable event with coordinates. Use sparingly — only events worth recalling later.');

const recallEpisodesSchema = z
  .object({
    event: z.string().optional(),
    limit: z.number().int().min(1).max(20).optional(),
  })
  .describe('Recall recent episodic events, optionally filtered by event tag.');

// ============================================================================
// Registry
// ============================================================================

export const TOOLS = {
  // I/O
  chat: chatSchema,

  // skill execution / management
  writeSkill: writeSkillSchema,
  invokeSkill: invokeSkillSchema,
  searchSkill: searchSkillSchema,
  runOnce: runOnceSchema,
  listSkills: listSkillsSchema,

  // knowledge RAG
  lookupRecipe: lookupRecipeSchema,
  lookupMob: lookupMobSchema,
  lookupBlock: lookupBlockSchema,
  findRecipesContaining: findRecipesContainingSchema,
  findMobsDropping: findMobsDroppingSchema,

  // grounding
  resolveReference: resolveReferenceSchema,

  // memory
  pinFact: pinFactSchema,
  recallPinnedFacts: recallPinnedFactsSchema,
  recordEpisodicEvent: recordEpisodicEventSchema,
  recallEpisodes: recallEpisodesSchema,
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
