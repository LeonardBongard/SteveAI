// v2 control loop with auto-injected bot state and Voyager-style skill
// dispatch. Per docs/COMPANION_V2_DIRECTION.md §§3.5 + 3.6.
//
// Flow:
//  1. Persist player turn into conversation memory.
//  2. Build context: system prompt + pinned facts + similar older turns +
//     sliding window + AUTO-INJECTED bot-state snapshot.
//  3. Tool loop (max MAX_TOOL_STEPS_PER_INTENT). Dispatch the v2 meta tools:
//     - I/O:        chat
//     - skills:     writeSkill / invokeSkill / searchSkill / runOnce / listSkills
//     - knowledge:  lookupRecipe / lookupMob / lookupBlock / findRecipesContaining /
//                   findMobsDropping
//     - grounding:  resolveReference
//     - memory:     pinFact / recallPinnedFacts / recordEpisodicEvent / recallEpisodes
//  4. Failures from inside skills / handlers come back as observation strings
//     in the same turn — DEPS-style. The LLM patches in the next round.
//
// What's GONE compared to v1:
//  - No goto / mineBlock / placeBlock / equipItem / followPlayer / stop /
//    sayToPlayer / findNearestBlock / getInventory / getNearbyEntities /
//    getTimeOfDay / getPlayerLocation tools. Mineflayer's API is now used
//    INSIDE skill code; perception is auto-injected.
//  - No hindsight playbook-capture step. The LLM does naming + describing
//    at writeSkill time.

import type { Bot } from 'mineflayer';
import type { Message } from 'ollama';
import { chat as llmChat } from './llm/ollama.js';
import { SYSTEM_PROMPT } from './llm/prompt.js';
import { isKnownTool, parseToolArgs, type ToolName } from './llm/tools.js';
import { conversation } from './memory/conversation.js';
import { episodic } from './memory/episodic.js';
import { skills } from './skills/library.js';
import { runSkillBody, runOnceCode } from './skills/sandbox.js';
import {
  lookupRecipe,
  lookupMob,
  lookupBlock,
  findRecipesContaining,
  findMobsDropping,
} from './knowledge/index.js';
import { resolveSpatialReference } from './grounding/reference.js';
import { confirmAndAct } from './grounding/confirm.js';
import { logs } from './log.js';

const MAX_TOOL_STEPS_PER_INTENT = 12;
const MEMORY_WINDOW_TURNS = Number(process.env.MEMORY_WINDOW_TURNS ?? '20');
const MEMORY_TOP_K = Number(process.env.MEMORY_TOP_K ?? '4');

// ============================================================================
// Public API
// ============================================================================

export interface PlannerHooks {
  onToolCall?: (info: {
    step: number;
    tool: string;
    args: unknown;
    observation: string;
    durationMs: number;
  }) => void;
}

export interface PlannerOptions {
  hooks?: PlannerHooks;
  awaitCapture?: boolean; // unused in v2 (skills are saved at writeSkill time, not after); kept for API stability
}

export interface PlannerResult {
  steps: number;
  hitStepLimit: boolean;
  captured: boolean; // true if any new skill was written this turn
  trace: string[];
  lastAssistant: string;
  durationMs: number;
}

interface PlannerCtx {
  bot: Bot;
  speakerName: string;
}

// ============================================================================
// Tool dispatch table — v2 surface
// ============================================================================

type Handler = (args: unknown, ctx: PlannerCtx, planner: PlannerState) => Promise<string>;

interface PlannerState {
  /** Set when a skill was written or invoked successfully this turn. */
  capturedThisTurn: boolean;
  /** Skill execution log this turn — fed back into planner state. */
  skillLog: Array<{ name: string; ok: boolean; error?: string }>;
}

const HANDLERS: Record<ToolName, Handler> = {
  // --- I/O ---
  chat: async (a, { bot }) => {
    const args = parseToolArgs('chat', a);
    safeSay(bot, args.message);
    return `said: ${truncate(args.message, 80)}`;
  },

  // --- Skills ---
  writeSkill: async (a, _ctx, state) => {
    const args = parseToolArgs('writeSkill', a);
    try {
      const skill = await skills.save(args.name, args.description, args.code);
      state.capturedThisTurn = true;
      return `saved skill "${skill.name}" (${args.code.length} chars). invoke it to run.`;
    } catch (err) {
      return `failed: writeSkill: ${(err as Error).message}`;
    }
  },

  invokeSkill: async (a, { bot }, state) => {
    const args = parseToolArgs('invokeSkill', a);
    const skill = skills.get(args.name);
    if (!skill) return `failed: invokeSkill: no skill named "${args.name}". Use searchSkill or writeSkill first.`;
    const result = await runSkillBody(skill.code, bot, args.args ?? {});
    if (result.ok) {
      skills.recordSuccess(skill.name);
      state.skillLog.push({ name: skill.name, ok: true });
      const valueStr = result.value === undefined ? '' : ` returned ${formatValue(result.value)}`;
      return `invoked ${skill.name} ok in ${result.durationMs}ms${valueStr}`;
    }
    skills.recordFailure(skill.name, result.error ?? '');
    state.skillLog.push({ name: skill.name, ok: false, ...(result.error ? { error: result.error } : {}) });
    return `failed: ${skill.name}: ${result.error ?? 'unknown error'}`;
  },

  searchSkill: async (a) => {
    const args = parseToolArgs('searchSkill', a);
    const hits = await skills.search(args.query, args.k ?? 4);
    if (hits.length === 0) return 'no matching skills in library';
    return hits
      .map((h) => `${h.name} (✓${h.successCount}/✗${h.failureCount}): ${h.description}`)
      .join('; ');
  },

  runOnce: async (a, { bot }) => {
    const args = parseToolArgs('runOnce', a);
    const result = await runOnceCode(args.code, bot);
    if (result.ok) {
      const valueStr = result.value === undefined ? '' : ` returned ${formatValue(result.value)}`;
      return `runOnce ok in ${result.durationMs}ms${valueStr}`;
    }
    return `failed: runOnce: ${result.error ?? 'unknown error'}`;
  },

  listSkills: async (a) => {
    const args = parseToolArgs('listSkills', a);
    const list = skills.list(args.limit ?? 20);
    if (list.length === 0) return 'skill library is empty';
    return list
      .map((s) => `${s.name} (✓${s.successCount}/✗${s.failureCount})`)
      .join(', ');
  },

  // --- Knowledge RAG ---
  lookupRecipe: async (a) => {
    const args = parseToolArgs('lookupRecipe', a);
    const recipes = lookupRecipe(args.item);
    if (recipes.length === 0) return `no recipes for ${args.item}`;
    return recipes
      .map((r) => formatRecipe(r))
      .join(' | ');
  },

  lookupMob: async (a) => {
    const args = parseToolArgs('lookupMob', a);
    const mob = lookupMob(args.name);
    if (!mob) return `no data for mob ${args.name}`;
    if (mob.drops.length === 0) return `${mob.displayName}: no known drops`;
    const dropList = mob.drops
      .map((d) => `${d.item} ×${d.countRange[0]}-${d.countRange[1]} @ ${Math.round(d.chance * 100)}%`)
      .join(', ');
    return `${mob.displayName}: drops ${dropList}`;
  },

  lookupBlock: async (a) => {
    const args = parseToolArgs('lookupBlock', a);
    const block = lookupBlock(args.name);
    if (!block) return `no data for block ${args.name}`;
    const tools = block.harvestTools.length > 0 ? `harvest with: ${block.harvestTools.join('/')}` : 'no required tool';
    const drops = block.drops.length > 0 ? `drops ${block.drops.join(', ')}` : 'drops nothing';
    return `${block.displayName}: hardness ${block.hardness ?? '?'}; ${tools}; ${drops}`;
  },

  findRecipesContaining: async (a) => {
    const args = parseToolArgs('findRecipesContaining', a);
    const hits = findRecipesContaining(args.item);
    if (hits.length === 0) return `nothing crafts using ${args.item}`;
    return hits.slice(0, 8).map((r) => `${r.result.name} (uses ${r.ingredients.map((i) => i.name).join('+')})`).join(', ');
  },

  findMobsDropping: async (a) => {
    const args = parseToolArgs('findMobsDropping', a);
    const hits = findMobsDropping(args.item);
    if (hits.length === 0) return `no mobs drop ${args.item}`;
    return hits.slice(0, 8).map((m) => m.displayName).join(', ');
  },

  // --- Grounding ---
  resolveReference: async (a, ctx) => {
    const args = parseToolArgs('resolveReference', a);
    const ref = await resolveSpatialReference(args.phrase, ctx);
    if (!ref) return `could not resolve "${args.phrase}" — ask the player to be more specific`;
    const confirmation = confirmAndAct(ctx.bot, ref, { intent: args.verb ?? 'on it' });
    return formatResolved(ref, confirmation);
  },

  // --- Memory ---
  pinFact: async (a) => {
    const args = parseToolArgs('pinFact', a);
    conversation.pinFact(args.key, args.value, args.context);
    return `pinned ${args.key} = ${args.value}`;
  },

  recallPinnedFacts: async () => {
    const facts = conversation.pinnedFacts();
    if (facts.length === 0) return 'no pinned facts';
    return facts.map((f) => `${f.key}=${f.value}`).join('; ');
  },

  recordEpisodicEvent: async (a, { bot }) => {
    const args = parseToolArgs('recordEpisodicEvent', a);
    const recorded = episodic.recordEvent({
      event: args.event,
      x: args.x,
      y: args.y,
      z: args.z,
      dimension: args.dimension ?? bot.game.dimension ?? 'overworld',
      context: args.context ?? '',
    });
    return `recorded ${recorded.event} at (${recorded.x}, ${recorded.y}, ${recorded.z})`;
  },

  recallEpisodes: async (a) => {
    const args = parseToolArgs('recallEpisodes', a);
    const events = episodic.recent({
      ...(args.event ? { event: args.event } : {}),
      limit: args.limit ?? 5,
    });
    if (events.length === 0) return 'no episodic memories';
    return events
      .map((e) => `${e.event} at (${e.x}, ${e.y}, ${e.z}): ${e.context}`)
      .join('; ');
  },
};

// ============================================================================
// Public entry point
// ============================================================================

export async function handlePlayerChat(
  bot: Bot,
  speakerName: string,
  message: string,
  options: PlannerOptions = {}
): Promise<PlannerResult> {
  const turnStartedAt = Date.now();
  const hooks = options.hooks ?? {};
  logs.plan.info({ speaker: speakerName, message }, 'turn start');

  await conversation.recordTurn(`player:${speakerName}`, message);

  const window = conversation.window(MEMORY_WINDOW_TURNS);
  const pinned = conversation.pinnedFacts();
  const similar = await conversation.retrieveSimilar(message, MEMORY_TOP_K, MEMORY_WINDOW_TURNS);
  const botState = snapshotBotState(bot);

  const messages = buildContext({ window, pinned, similar, botState });

  const ctx: PlannerCtx = { bot, speakerName };
  const state: PlannerState = { capturedThisTurn: false, skillLog: [] };
  const trace: string[] = [];
  let lastAssistant = '';
  let stepIdx = 0;

  for (; stepIdx < MAX_TOOL_STEPS_PER_INTENT; stepIdx++) {
    let result;
    try {
      result = await llmChat(messages);
    } catch (err) {
      const reason = (err as Error).message;
      logs.plan.error({ err: reason, step: stepIdx }, 'LLM call failed');
      bot.chat('(my brain just hiccuped — try again?)');
      return resultOf(stepIdx, false, state, trace, lastAssistant, turnStartedAt);
    }

    const text = result.content.trim();
    if (text) lastAssistant = text;

    if (result.toolCalls.length === 0) {
      if (text) {
        bot.chat(truncateForChat(text));
        trace.push(`said: ${shortDesc(text)}`);
      }
      messages.push({ role: 'assistant', content: text });
      break;
    }

    messages.push({
      role: 'assistant',
      content: text,
      tool_calls: result.toolCalls.map((tc) => ({
        function: { name: tc.name, arguments: tc.args as Record<string, unknown> },
      })),
    } as Message);

    for (const call of result.toolCalls) {
      const callStart = Date.now();
      const observation = await dispatch(call.name, call.args, ctx, state);
      const callDuration = Date.now() - callStart;
      trace.push(`${call.name}: ${shortDesc(observation)}`);
      hooks.onToolCall?.({
        step: stepIdx + 1,
        tool: call.name,
        args: call.args,
        observation,
        durationMs: callDuration,
      });
      messages.push({ role: 'tool', content: observation } as Message);
    }
  }

  if (stepIdx >= MAX_TOOL_STEPS_PER_INTENT) {
    logs.plan.warn({ steps: stepIdx }, 'hit max tool steps; bailing turn');
    bot.chat('(I hit a step limit — pausing. ask me to continue if needed.)');
  }

  if (lastAssistant) {
    await conversation.recordTurn('steve', lastAssistant);
  }

  logs.plan.info(
    { speaker: speakerName, steps: stepIdx, captured: state.capturedThisTurn, skills: state.skillLog },
    'turn end'
  );

  return resultOf(stepIdx, stepIdx >= MAX_TOOL_STEPS_PER_INTENT, state, trace, lastAssistant, turnStartedAt);
}

function resultOf(
  steps: number,
  hitStepLimit: boolean,
  state: PlannerState,
  trace: string[],
  lastAssistant: string,
  startedAt: number
): PlannerResult {
  return {
    steps,
    hitStepLimit,
    captured: state.capturedThisTurn,
    trace,
    lastAssistant,
    durationMs: Date.now() - startedAt,
  };
}

// ============================================================================
// Context building
// ============================================================================

function buildContext(args: {
  window: Array<{ speaker: string; message: string }>;
  pinned: Array<{ key: string; value: string }>;
  similar: Array<{ speaker: string; message: string }>;
  botState: string;
}): Message[] {
  const out: Message[] = [{ role: 'system', content: SYSTEM_PROMPT }];

  // Auto-injected bot state — Voyager pattern. Per turn, free.
  out.push({
    role: 'system',
    content: `[BOT STATE]\n${args.botState}`,
  });

  if (args.pinned.length > 0) {
    const lines = args.pinned.map((f) => `- ${f.key}: ${f.value}`).join('\n');
    out.push({ role: 'system', content: `Pinned facts about the player:\n${lines}` });
  }

  if (args.similar.length > 0) {
    const lines = args.similar.map((t) => `- ${t.speaker}: ${t.message}`).join('\n');
    out.push({ role: 'system', content: `Possibly relevant earlier exchanges:\n${lines}` });
  }

  for (const turn of args.window) {
    if (turn.speaker.startsWith('player:')) {
      const name = turn.speaker.slice('player:'.length);
      out.push({ role: 'user', content: `${name}: ${turn.message}` });
    } else if (turn.speaker === 'steve') {
      out.push({ role: 'assistant', content: turn.message });
    }
  }

  return out;
}

// Utility blocks the LLM commonly needs to know are nearby (so it doesn't
// have to runOnce-probe for them every craft request). Kept short.
const UTILITY_BLOCKS = [
  'crafting_table',
  'furnace',
  'blast_furnace',
  'smoker',
  'chest',
  'ender_chest',
  'anvil',
  'enchanting_table',
  'brewing_stand',
  'cauldron',
  'bed',
];

function snapshotBotState(bot: Bot): string {
  const p = bot.entity?.position;
  const pos = p ? `(${rnd(p.x)}, ${rnd(p.y)}, ${rnd(p.z)})` : '(unknown)';
  const dim = bot.game?.dimension ?? 'overworld';
  const health = bot.health !== undefined ? `${rnd(bot.health)}/20` : '?';
  const food = bot.food !== undefined ? `${bot.food}/20` : '?';
  const inv = (() => {
    try {
      const items = bot.inventory?.items() ?? [];
      if (items.length === 0) return 'empty';
      return items.map((i) => `${i.count}x ${i.name}`).join(', ');
    } catch {
      return '?';
    }
  })();
  const time = bot.time
    ? `${bot.time.isDay ? 'day' : 'night'}, tick ${bot.time.timeOfDay ?? bot.time.time ?? '?'}`
    : '?';
  const nearbyEntities = (() => {
    try {
      const me = bot.entity?.position;
      if (!me) return 'none';
      const list: string[] = [];
      for (const e of Object.values(bot.entities ?? {})) {
        if (!e?.position || e.id === bot.entity?.id) continue;
        const d = e.position.distanceTo(me);
        if (d > 32) continue;
        list.push(`${e.username ?? e.name ?? 'entity'}@${rnd(d)}m`);
      }
      list.sort();
      if (list.length === 0) return 'none';
      return list.slice(0, 8).join(', ');
    } catch {
      return '?';
    }
  })();
  const utilities = (() => {
    try {
      const reg = bot.registry?.blocksByName;
      if (!reg) return 'none';
      const found: string[] = [];
      for (const name of UTILITY_BLOCKS) {
        const def = reg[name];
        if (!def) continue;
        const block = bot.findBlock?.({ matching: def.id, maxDistance: 24 });
        if (block) {
          found.push(`${name}@(${block.position.x},${block.position.y},${block.position.z})`);
        }
      }
      if (found.length === 0) return 'none';
      return found.join(', ');
    } catch {
      return '?';
    }
  })();

  return [
    `position: ${pos} ${dim}`,
    `health: ${health}  food: ${food}`,
    `inventory: ${inv}`,
    `nearby_entities: ${nearbyEntities}`,
    `nearby_utility_blocks: ${utilities}`,
    `time: ${time}`,
  ].join('\n');
}

// ============================================================================
// Tool dispatch
// ============================================================================

async function dispatch(
  name: string,
  rawArgs: unknown,
  ctx: PlannerCtx,
  state: PlannerState
): Promise<string> {
  if (!isKnownTool(name)) return `failed: unknown tool ${name}`;
  try {
    return await HANDLERS[name](rawArgs, ctx, state);
  } catch (err) {
    const reason = (err as Error).message;
    logs.act.warn({ tool: name, err: reason }, 'tool dispatch failed');
    return `failed: ${name}: ${reason}`;
  }
}

// ============================================================================
// Helpers
// ============================================================================

function safeSay(bot: Bot, text: string): void {
  const trimmed = text.trim();
  if (trimmed.length === 0) return;
  const MAX = 240;
  for (let i = 0; i < trimmed.length; i += MAX) {
    bot.chat(trimmed.slice(i, i + MAX));
  }
}

function shortDesc(s: string): string {
  const trimmed = s.trim();
  return trimmed.length > 120 ? trimmed.slice(0, 117) + '...' : trimmed;
}

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

function truncateForChat(s: string): string {
  const oneLine = s.replace(/\s+/g, ' ').trim();
  return oneLine.length > 240 ? oneLine.slice(0, 237) + '...' : oneLine;
}

function rnd(n: number): number {
  return Math.round(n * 10) / 10;
}

function formatValue(v: unknown): string {
  if (v === null) return 'null';
  if (typeof v === 'string') return JSON.stringify(v.length > 80 ? v.slice(0, 80) + '…' : v);
  if (typeof v === 'object') {
    try {
      const s = JSON.stringify(v);
      return s.length > 200 ? s.slice(0, 200) + '…' : s;
    } catch {
      return '[object]';
    }
  }
  return String(v);
}

function formatRecipe(r: { result: { name: string; count: number }; ingredients: Array<{ name: string; count: number }>; station: string }): string {
  const ings = r.ingredients.map((i) => `${i.count}x ${i.name}`).join(' + ');
  return `${r.result.count}x ${r.result.name} = ${ings} (${r.station})`;
}

function formatResolved(
  ref: { kind: string; description: string; position?: { x: number; y: number; z: number } | undefined; source: string },
  confirmation: string
): string {
  const base = `resolved [${ref.kind}/${ref.source}]: ${ref.description}`;
  if (ref.position) {
    return `${base}; coords (${ref.position.x}, ${ref.position.y}, ${ref.position.z}); confirmed in chat: "${confirmation}"`;
  }
  return `${base}; confirmed in chat: "${confirmation}"`;
}
