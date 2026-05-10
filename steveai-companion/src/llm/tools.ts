// Tool schemas exposed to gpt-oss. Defined with Zod, converted to JSON Schema
// for Ollama's tool-calling API (which follows the OpenAI shape).
//
// Phase 1: only chat. Subsequent phases (memory, grounding, actions) extend
// this list. Keep schemas terse — every word here gets KV-cached and re-sent
// per turn.

import { z } from 'zod';
import { zodToJsonSchema } from 'zod-to-json-schema';

// --- Schemas ---

const sayToPlayerSchema = z
  .object({
    message: z.string().min(1).max(256).describe('What to say in in-game chat. Keep it short.'),
  })
  .describe('Send a chat message visible to the player.');

// Future tools (uncomment as the corresponding modules land):
// const findBlockSchema = z.object({ ... });
// const gotoSchema = z.object({ ... });
// const digBlockSchema = z.object({ ... });
// const getInventorySchema = z.object({});
// const getNearbyEntitiesSchema = z.object({ ... });
// const resolveSpatialReferenceSchema = z.object({ ... });

// --- Registry ---

export const TOOLS = {
  sayToPlayer: sayToPlayerSchema,
} as const;

export type ToolName = keyof typeof TOOLS;
export type ToolArgs<N extends ToolName> = z.infer<(typeof TOOLS)[N]>;

// Ollama-compatible tool schema (OpenAI shape).
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

// Strict argument parsing. Returns a typed result or throws a Zod error
// the planner can convert into a DEPS-style failure-context string.
export function parseToolArgs<N extends ToolName>(name: N, raw: unknown): ToolArgs<N> {
  const schema = TOOLS[name];
  return schema.parse(raw) as ToolArgs<N>;
}
