// Thin Ollama client.
//
// Design notes (see docs/COMPANION_V1_DIRECTION.md §3.7):
// - We pass `keep_alive` on every request so the daemon's eviction policy
//   matches the bot's expectation, even if the daemon's launchctl env wasn't
//   set. Default 30m — tuned for "Mac with Minecraft also running" (#9).
// - Tool-calling uses Ollama's native OpenAI-compatible shape (#10).
// - Streaming is supported by passing { stream: true } at the call site;
//   phase 1 keeps it simple and uses non-streaming.

import { Ollama, type Message, type Tool } from 'ollama';
import { logs } from '../log.js';
import { buildOllamaTools } from './tools.js';

const HOST = process.env.OLLAMA_HOST ?? 'http://localhost:11434';
const MODEL = process.env.OLLAMA_MODEL ?? 'gpt-oss:20b';
const EMBED_MODEL = process.env.OLLAMA_EMBED_MODEL ?? 'nomic-embed-text';
const KEEP_ALIVE = process.env.OLLAMA_KEEP_ALIVE ?? '30m';

const client = new Ollama({ host: HOST });

export interface ChatResult {
  content: string;
  toolCalls: Array<{ name: string; args: unknown }>;
  promptTokens?: number;
  evalTokens?: number;
  totalDurationMs?: number;
}

/**
 * One round-trip to gpt-oss. Returns the model's text plus any tool calls.
 * Does NOT execute the tool calls — that's the planner's job.
 */
export async function chat(messages: Message[]): Promise<ChatResult> {
  const tools = buildOllamaTools() as unknown as Tool[];
  const start = Date.now();

  const res = await client.chat({
    model: MODEL,
    messages,
    tools,
    stream: false,
    keep_alive: KEEP_ALIVE,
    options: {
      // Low temp for tool-call discipline. Higher temps push gpt-oss into
      // prose chain-of-thought that Ollama mis-parses as text+JSON tool
      // calls (we hit this in eval scenario 06).
      temperature: 0.2,
    },
  });

  const totalDurationMs = Date.now() - start;
  const toolCalls = (res.message.tool_calls ?? []).map((tc) => ({
    name: tc.function.name,
    args: tc.function.arguments,
  }));

  logs.llm.debug(
    {
      model: MODEL,
      tookMs: totalDurationMs,
      promptTokens: res.prompt_eval_count,
      evalTokens: res.eval_count,
      toolCalls: toolCalls.length,
    },
    'chat round-trip'
  );

  return {
    content: res.message.content ?? '',
    toolCalls,
    promptTokens: res.prompt_eval_count,
    evalTokens: res.eval_count,
    totalDurationMs,
  };
}

/** Embedding lookup for memory retrieval. Used by §3.3 conversational memory. */
export async function embed(text: string): Promise<number[]> {
  const res = await client.embeddings({
    model: EMBED_MODEL,
    prompt: text,
    keep_alive: KEEP_ALIVE,
  });
  return res.embedding;
}

/** Health check — call once at startup so we fail fast if Ollama is down. */
export async function healthCheck(): Promise<{ ok: boolean; details: string }> {
  try {
    const list = await client.list();
    const names = list.models.map((m) => m.name);
    const hasModel = names.some((n) => n === MODEL || n.startsWith(MODEL.split(':')[0] ?? ''));
    if (!hasModel) {
      return {
        ok: false,
        details: `Ollama is up at ${HOST}, but model '${MODEL}' is not pulled. Run: ollama pull ${MODEL}`,
      };
    }
    return { ok: true, details: `Ollama up at ${HOST}, model ${MODEL} available.` };
  } catch (err) {
    return {
      ok: false,
      details: `Cannot reach Ollama at ${HOST}: ${(err as Error).message}. Run \`ollama serve\` or open the Ollama app.`,
    };
  }
}

export const config = {
  host: HOST,
  model: MODEL,
  embedModel: EMBED_MODEL,
  keepAlive: KEEP_ALIVE,
};
