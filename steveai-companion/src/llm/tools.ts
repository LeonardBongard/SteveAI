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
    prerequisites: z.array(z.string()).optional().describe('Optional list of OTHER skill names this skill depends on (i.e. skills this one INVOKES inside its body, or skills that produce items this one needs). The planner will warn if you invoke this skill while any prerequisite is missing or unverified. Use this for composed skills: e.g. craft_wooden_pickaxe lists prerequisites=["craft_oak_planks","craft_sticks","craft_crafting_table"].'),
    producesItems: z.array(z.string()).optional().describe('Optional list of item names this skill is EXPECTED to add to the inventory when it succeeds (e.g. ["oak_planks"], ["wooden_pickaxe"]). Best-effort declaration. The planner uses this for reverse-prereq lookup ("which skill produces X?") and for after-action checks.'),
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

const lookupTechTreeSchema = z
  .object({
    item: z.string().min(1).describe('Target item name, e.g. "iron_pickaxe".'),
    maxDepth: z.number().int().min(1).max(6).optional().describe('How deep to chase prerequisites. Default 4 (e.g. iron_pickaxe → iron_ingot → iron_ore covers it).'),
  })
  .describe('Walk the recipe graph backwards to raw materials. Returns the full prerequisite chain in one call: e.g. iron_pickaxe needs 3x iron_ingot (which needs furnace + iron_ore + fuel) plus 2x stick (which needs 1x planks). Use BEFORE planning multi-step crafting tasks.');

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
// Goal stack (T1.1) — multi-turn intent tracking
// ============================================================================

const pushGoalSchema = z
  .object({
    text: z.string().min(1).max(200).describe('Short description of the goal, e.g. "get player a wooden pickaxe".'),
    parentId: z.number().int().optional().describe('Optional id of an active parent goal; this becomes a sub-goal.'),
  })
  .describe('Push a new goal onto the stack. Use when the player gives a new high-level ask, or when you decompose an existing goal into sub-steps.');

const completeGoalSchema = z
  .object({
    id: z.number().int(),
  })
  .describe('Mark a goal done. Cascades to its active sub-goals. Call this when you have actually completed the work the goal describes.');

const cancelGoalSchema = z
  .object({
    id: z.number().int(),
  })
  .describe('Cancel a goal (and its sub-goals). Use when the player explicitly drops the request or you determine the goal is unreachable.');

const listGoalsSchema = z
  .object({})
  .describe('List all currently active goals. Useful for orienting at the start of a turn; the active goals are also auto-injected into bot state.');

// ============================================================================
// After-action review (T2.4) — recall what happened on prior skill invocations
// ============================================================================

const afterActionReviewSchema = z
  .object({
    skillName: z.string().optional().describe('Optional: filter to one specific skill name.'),
    limit: z.number().int().min(1).max(20).optional().describe('How many recent trials to fetch. Default 6.'),
  })
  .describe('Review recent skill invocations: success/failure, duration, inventory + nearby utilities at the time, and the error if any. Use when the player asks "what just happened?" or when diagnosing why a skill keeps failing. Reads from the experience pool, not from chat history.');

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
  lookupTechTree: lookupTechTreeSchema,

  // grounding
  resolveReference: resolveReferenceSchema,

  // memory
  pinFact: pinFactSchema,
  recallPinnedFacts: recallPinnedFactsSchema,
  recordEpisodicEvent: recordEpisodicEventSchema,
  recallEpisodes: recallEpisodesSchema,
  // goals (T1.1)
  pushGoal: pushGoalSchema,
  completeGoal: completeGoalSchema,
  cancelGoal: cancelGoalSchema,
  listGoals: listGoalsSchema,

  // after-action review (T2.4)
  afterActionReview: afterActionReviewSchema,
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
