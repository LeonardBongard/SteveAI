// Phase-1 bot entry: connect, listen for chat, round-trip the LLM, reply.
//
// What's NOT here yet (per docs/COMPANION_V1_DIRECTION.md §5):
// - persistent memory (phase 2)
// - spatial grounding (phase 2)
// - active perception tools beyond `sayToPlayer` (phase 2)
// - intent-based control loop with DEPS-style failure injection (phase 2)
// - eval harness (phase 3)
//
// This file is intentionally tight. It exists to prove the stack: Mineflayer
// connects, chat triggers an LLM call, gpt-oss replies via the sayToPlayer
// tool, and the message lands in chat.

import 'dotenv/config';
import mineflayer from 'mineflayer';
import type { Message } from 'ollama';
import { logs } from './log.js';
import { chat, healthCheck, config as llmConfig } from './llm/ollama.js';
import { SYSTEM_PROMPT } from './llm/prompt.js';
import { parseToolArgs, type ToolName } from './llm/tools.js';

// --- Config ---

const MC_HOST = process.env.MC_HOST ?? 'localhost';
const MC_PORT = Number(process.env.MC_PORT ?? '25565');
const MC_USERNAME = process.env.MC_USERNAME ?? 'Steve';
// "false" or unset means: let Mineflayer auto-negotiate the protocol version.
const MC_VERSION =
  !process.env.MC_VERSION || process.env.MC_VERSION === 'false'
    ? undefined
    : process.env.MC_VERSION;
const MC_AUTH = (process.env.MC_AUTH ?? 'offline') as 'offline' | 'microsoft' | 'mojang';

const MEMORY_WINDOW_TURNS = Number(process.env.MEMORY_WINDOW_TURNS ?? '20');

// --- Sliding-window conversation buffer (phase-1 stand-in) ---
// Phase 2 replaces this with the full memory module (sliding window + pinned
// facts + vector retrieval; see docs §3.3). Kept here so phase 1 already works
// in a shape we don't have to throw away.

const conversation: Message[] = [];

function pushTurn(role: 'user' | 'assistant', content: string): void {
  conversation.push({ role, content });
  // Trim to the last N turns; keep system prompt out of this buffer (it's
  // injected fresh every call to preserve the cached prefix).
  const cap = MEMORY_WINDOW_TURNS * 2; // user+assistant per turn
  if (conversation.length > cap) {
    conversation.splice(0, conversation.length - cap);
  }
}

// --- Bot lifecycle ---

async function main(): Promise<void> {
  logs.bot.info({ llm: llmConfig }, 'starting steveai-companion v0.1.0 (phase 1)');

  const health = await healthCheck();
  if (!health.ok) {
    logs.bot.error({ details: health.details }, 'ollama health check failed; exiting');
    process.exit(1);
  }
  logs.bot.info(health.details);

  const bot = mineflayer.createBot({
    host: MC_HOST,
    port: MC_PORT,
    username: MC_USERNAME,
    auth: MC_AUTH,
    ...(MC_VERSION ? { version: MC_VERSION } : {}),
  });

  bot.once('spawn', () => {
    logs.bot.info(
      {
        host: MC_HOST,
        port: MC_PORT,
        username: MC_USERNAME,
        position: bot.entity.position,
      },
      'bot spawned'
    );
    bot.chat("hi! I'm Steve. say something to me in chat.");
  });

  bot.on('chat', (username, message) => {
    if (username === bot.username) return; // ignore our own messages
    logs.bot.info({ from: username, message }, 'incoming chat');
    void handleChat(bot, username, message);
  });

  bot.on('kicked', (reason) => {
    logs.bot.error({ reason }, 'bot kicked');
  });

  bot.on('error', (err) => {
    logs.bot.error({ err: err.message }, 'mineflayer error');
  });

  bot.on('end', (reason) => {
    logs.bot.warn({ reason }, 'bot disconnected');
    process.exit(0);
  });
}

async function handleChat(
  bot: mineflayer.Bot,
  speaker: string,
  message: string
): Promise<void> {
  // Build the messages array fresh each turn. The system prompt comes first
  // so Ollama's KV-cache can reuse the prefix across calls.
  const userTurn = `${speaker}: ${message}`;
  pushTurn('user', userTurn);

  const messages: Message[] = [
    { role: 'system', content: SYSTEM_PROMPT },
    ...conversation,
  ];

  let result;
  try {
    result = await chat(messages);
  } catch (err) {
    logs.llm.error({ err: (err as Error).message }, 'LLM call failed');
    bot.chat("(my brain just hiccuped, sorry — try again?)");
    return;
  }

  // Record the assistant turn (text portion) regardless of tool calls.
  if (result.content.trim().length > 0) {
    pushTurn('assistant', result.content);
  }

  // Execute tool calls. Phase 1 only knows about `sayToPlayer`.
  for (const call of result.toolCalls) {
    await executeTool(bot, call.name as ToolName, call.args);
  }

  // If the model didn't tool-call but did emit text, fall back to chatting it.
  if (result.toolCalls.length === 0 && result.content.trim().length > 0) {
    safeSay(bot, result.content);
  }
}

async function executeTool(
  bot: mineflayer.Bot,
  name: ToolName,
  rawArgs: unknown
): Promise<void> {
  try {
    switch (name) {
      case 'sayToPlayer': {
        const args = parseToolArgs('sayToPlayer', rawArgs);
        safeSay(bot, args.message);
        return;
      }
      default: {
        // Exhaustive check — TypeScript will yell if we add a tool and forget.
        const _exhaustive: never = name;
        logs.act.warn({ name: _exhaustive, rawArgs }, 'unknown tool call');
      }
    }
  } catch (err) {
    logs.act.error(
      { name, rawArgs, err: (err as Error).message },
      'tool execution failed'
    );
    // DEPS-style: the next turn's context will see this in conversation. Phase 1
    // doesn't yet inject failure context cleanly; phase 2's planner does.
  }
}

/** Mineflayer truncates very long chat lines; split conservatively. */
function safeSay(bot: mineflayer.Bot, text: string): void {
  const trimmed = text.trim();
  if (trimmed.length === 0) return;
  // Vanilla chat caps at 256 chars; keep a margin.
  const MAX = 240;
  for (let i = 0; i < trimmed.length; i += MAX) {
    bot.chat(trimmed.slice(i, i + MAX));
  }
}

main().catch((err) => {
  logs.bot.fatal({ err: (err as Error).message, stack: (err as Error).stack }, 'fatal');
  process.exit(1);
});
