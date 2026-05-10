// Intent-based control loop with DEPS-style failure injection.
//
// Per docs/COMPANION_V1_DIRECTION.md §3.5 + §3.7 #4:
// - LLM plans an intent (3–10 actions). Bot executes them autonomously.
// - LLM only re-engages on success / failure / new chat / novelty.
// - Failures inject the cause into the next turn's context (DEPS pattern);
//   we do not full-replan from scratch.
//
// Per §3.3 + §3.7 #2: on verified success, the trajectory is captured
// hindsightly into the playbook (one LLM call to name + describe).

import type { Bot } from 'mineflayer';
import type { Message } from 'ollama';
import { chat } from './llm/ollama.js';
import { SYSTEM_PROMPT } from './llm/prompt.js';
import { isKnownTool, parseToolArgs, type ToolName } from './llm/tools.js';
import { conversation } from './memory/conversation.js';
import { playbook } from './memory/playbook.js';
import { logs } from './log.js';

import {
  actSayToPlayer,
  actGoto,
  actMineBlock,
  actPlaceBlock,
  actEquipItem,
  actFollowPlayer,
  actStop,
} from './actions/handlers.js';
import {
  getPlayerLocation,
  getInventory,
  findNearestBlock,
  getNearbyEntities,
  getTimeOfDay,
  resolveReference,
  pinFact,
  recallPinnedFacts,
  recordEpisodicEvent,
  recallEpisodes,
  searchPlaybook,
} from './perception/handlers.js';

// ============================================================================
// Tunables
// ============================================================================

const MAX_TOOL_STEPS_PER_INTENT = 12;
const MEMORY_WINDOW_TURNS = Number(process.env.MEMORY_WINDOW_TURNS ?? '20');
const MEMORY_TOP_K = Number(process.env.MEMORY_TOP_K ?? '4');

// ============================================================================
// Dispatch table — one row per ToolName, narrow types, single source of truth.
// ============================================================================

interface PlannerCtx {
  bot: Bot;
  speakerName: string;
}

type Handler = (args: unknown, ctx: PlannerCtx) => Promise<string>;

const HANDLERS: Record<ToolName, Handler> = {
  sayToPlayer: (a, c) => actSayToPlayer(parseToolArgs('sayToPlayer', a), c),
  goto: (a, c) => actGoto(parseToolArgs('goto', a), c),
  mineBlock: (a, c) => actMineBlock(parseToolArgs('mineBlock', a), c),
  placeBlock: (a, c) => actPlaceBlock(parseToolArgs('placeBlock', a), c),
  equipItem: (a, c) => actEquipItem(parseToolArgs('equipItem', a), c),
  followPlayer: (a, c) => actFollowPlayer(parseToolArgs('followPlayer', a), c),
  stop: (a, c) => actStop(parseToolArgs('stop', a), c),

  getPlayerLocation: (a, c) => getPlayerLocation(parseToolArgs('getPlayerLocation', a), c),
  getInventory: (a, c) => getInventory(parseToolArgs('getInventory', a), c),
  findNearestBlock: (a, c) => findNearestBlock(parseToolArgs('findNearestBlock', a), c),
  getNearbyEntities: (a, c) => getNearbyEntities(parseToolArgs('getNearbyEntities', a), c),
  getTimeOfDay: (a, c) => getTimeOfDay(parseToolArgs('getTimeOfDay', a), c),
  resolveReference: (a, c) => resolveReference(parseToolArgs('resolveReference', a), c),

  pinFact: (a, c) => pinFact(parseToolArgs('pinFact', a), c),
  recallPinnedFacts: (a, c) => recallPinnedFacts(parseToolArgs('recallPinnedFacts', a), c),
  recordEpisodicEvent: (a, c) => recordEpisodicEvent(parseToolArgs('recordEpisodicEvent', a), c),
  recallEpisodes: (a, c) => recallEpisodes(parseToolArgs('recallEpisodes', a), c),
  searchPlaybook: (a, c) => searchPlaybook(parseToolArgs('searchPlaybook', a), c),
};

// ============================================================================
// Public entry point — called from bot.ts on every player chat.
// ============================================================================

export interface PlannerHooks {
  /** Called once per LLM tool call after it executes. Useful for the eval harness. */
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
  /** If true, await the playbook capture before returning. Default false (fire-and-forget — what production wants). */
  awaitCapture?: boolean;
}

export interface PlannerResult {
  steps: number;
  hitStepLimit: boolean;
  captured: boolean;
  trace: string[];
  lastAssistant: string;
  durationMs: number;
}

export async function handlePlayerChat(
  bot: Bot,
  speakerName: string,
  message: string,
  options: PlannerOptions = {}
): Promise<PlannerResult> {
  const turnStartedAt = Date.now();
  const hooks = options.hooks ?? {};
  logs.plan.info({ speaker: speakerName, message }, 'turn start');

  // 1. Persist the player turn (sliding window + retrieval index).
  await conversation.recordTurn(`player:${speakerName}`, message);

  // 2. Build context: window + pinned facts + retrieved older turns.
  const window = conversation.window(MEMORY_WINDOW_TURNS);
  const pinned = conversation.pinnedFacts();
  const similar = await conversation.retrieveSimilar(message, MEMORY_TOP_K, MEMORY_WINDOW_TURNS);

  const messages = buildContext({
    window,
    pinned,
    similar,
    speakerName,
  });

  // 3. Run the tool loop.
  const ctx: PlannerCtx = { bot, speakerName };
  const trace: string[] = [];
  let lastAssistant = '';
  let stepIdx = 0;

  for (; stepIdx < MAX_TOOL_STEPS_PER_INTENT; stepIdx++) {
    let result;
    try {
      result = await chat(messages);
    } catch (err) {
      const reason = (err as Error).message;
      logs.plan.error({ err: reason, step: stepIdx }, 'LLM call failed');
      bot.chat('(my brain just hiccuped — try again?)');
      return {
        steps: stepIdx,
        hitStepLimit: false,
        captured: false,
        trace,
        lastAssistant,
        durationMs: Date.now() - turnStartedAt,
      };
    }

    const text = result.content.trim();
    if (text) lastAssistant = text;

    // No tool calls = the model is done with this turn (or only chatting).
    if (result.toolCalls.length === 0) {
      if (text) {
        // Free-form chat reply (model didn't use sayToPlayer). Mirror it.
        bot.chat(truncateForChat(text));
        trace.push(`said: ${shortDesc(text)}`);
      }
      // Append the assistant message into the running messages array so any
      // future replan sees it (not strictly needed since we exit, but cleanly
      // captures intent).
      messages.push({ role: 'assistant', content: text });
      break;
    }

    // The assistant message MUST come before the tool messages it references,
    // or Ollama errors. Push the assistant turn (with the tool_calls preserved
    // by Ollama in result; we re-emit our own minimal record).
    messages.push({
      role: 'assistant',
      content: text,
      tool_calls: result.toolCalls.map((tc) => ({
        function: { name: tc.name, arguments: tc.args as Record<string, unknown> },
      })),
    } as Message);

    // Execute every tool call from this batch; collect observations as
    // tool-role messages.
    for (const call of result.toolCalls) {
      const callStart = Date.now();
      const observation = await dispatch(call.name, call.args, ctx);
      const callDuration = Date.now() - callStart;
      trace.push(`${call.name}: ${shortDesc(observation)}`);
      hooks.onToolCall?.({
        step: stepIdx + 1,
        tool: call.name,
        args: call.args,
        observation,
        durationMs: callDuration,
      });
      messages.push({
        role: 'tool',
        content: observation,
      } as Message);
    }
  }

  if (stepIdx >= MAX_TOOL_STEPS_PER_INTENT) {
    logs.plan.warn({ steps: stepIdx }, 'hit max tool steps; bailing turn');
    bot.chat('(I hit a step limit — pausing. ask me to continue if needed.)');
  }

  // 4. Persist the final assistant turn (only the visible chat, not tool noise).
  if (lastAssistant) {
    await conversation.recordTurn('steve', lastAssistant);
  }

  // 5. Hindsight playbook capture: only if the trajectory had at least one
  //    real action and didn't end in a failure. Heuristic for v1: any step
  //    starting with "failed" anywhere in the trace ⇒ skip capture.
  const hadAction = trace.some((s) => /^(goto|mineBlock|placeBlock|equipItem|followPlayer):/.test(s));
  const hadFailure = trace.some((s) => /failed|cannot|timeout/i.test(s));
  let captured = false;
  if (hadAction && !hadFailure) {
    if (options.awaitCapture) {
      try {
        const recipe = await playbook.capture({ intent: message, steps: trace });
        captured = recipe !== null;
      } catch (err) {
        logs.mem.warn({ err: (err as Error).message }, 'playbook.capture failed');
      }
    } else {
      void playbook.capture({ intent: message, steps: trace }).catch((err) =>
        logs.mem.warn({ err: (err as Error).message }, 'playbook.capture failed')
      );
      captured = true; // optimistic; the eval harness uses awaitCapture for accuracy
    }
  }

  logs.plan.info({ speaker: speakerName, steps: stepIdx, captured }, 'turn end');

  return {
    steps: stepIdx,
    hitStepLimit: stepIdx >= MAX_TOOL_STEPS_PER_INTENT,
    captured,
    trace,
    lastAssistant,
    durationMs: Date.now() - turnStartedAt,
  };
}

// ============================================================================
// Context builder — minimal-by-default per §3.7 #3.
// ============================================================================

function buildContext(args: {
  window: Array<{ speaker: string; message: string }>;
  pinned: Array<{ key: string; value: string }>;
  similar: Array<{ speaker: string; message: string }>;
  speakerName: string;
}): Message[] {
  const out: Message[] = [{ role: 'system', content: SYSTEM_PROMPT }];

  // Pinned facts as a system-side "what you know about the player" note.
  if (args.pinned.length > 0) {
    const lines = args.pinned.map((f) => `- ${f.key}: ${f.value}`).join('\n');
    out.push({
      role: 'system',
      content: `Pinned facts about the player:\n${lines}`,
    });
  }

  // Retrieved older turns (only if any). Marked clearly so the model knows
  // these are recalled, not part of the active conversation.
  if (args.similar.length > 0) {
    const lines = args.similar.map((t) => `- ${t.speaker}: ${t.message}`).join('\n');
    out.push({
      role: 'system',
      content: `Possibly relevant earlier exchanges:\n${lines}`,
    });
  }

  // Sliding window: replay verbatim as user/assistant turns.
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

// ============================================================================
// Tool dispatch — narrow names, parse args, run handler, capture failures.
// ============================================================================

async function dispatch(name: string, rawArgs: unknown, ctx: PlannerCtx): Promise<string> {
  if (!isKnownTool(name)) {
    return `failed: unknown tool ${name}`;
  }
  try {
    return await HANDLERS[name](rawArgs, ctx);
  } catch (err) {
    const reason = (err as Error).message;
    logs.act.warn({ tool: name, err: reason }, 'tool dispatch failed');
    return `failed: ${name}: ${reason}`;
  }
}

// ============================================================================
// Helpers
// ============================================================================

function shortDesc(s: string): string {
  const trimmed = s.trim();
  return trimmed.length > 120 ? trimmed.slice(0, 117) + '...' : trimmed;
}

function truncateForChat(s: string): string {
  // Vanilla chat caps at 256; we also strip multi-line "thinking" output.
  const oneLine = s.replace(/\s+/g, ' ').trim();
  return oneLine.length > 240 ? oneLine.slice(0, 237) + '...' : oneLine;
}
