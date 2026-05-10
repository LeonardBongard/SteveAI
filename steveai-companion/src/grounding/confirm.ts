// Chat-back confirmation for grounded references.
//
// Per docs/COMPANION_V1_DIRECTION.md §3.4 + §3.7 #5: after we resolve a
// spatial reference, the bot says back what it understood ("the iron ore
// at (32, 64, -120) — mining now") before acting. Eliminates the entire
// "meant the OTHER one" failure class. Trivially cheap.
//
// We also track the confirmation state so the planner can detect when the
// player corrects us ("no, the OTHER iron"). For v1 the policy is simple:
// say it, do it. A future iteration can add an explicit yes/no hold.

import type { Bot } from 'mineflayer';
import type { ResolvedReference } from './reference.js';

export interface ConfirmOptions {
  intent?: string;            // e.g. "mining", "going to", "attacking"
  silent?: boolean;           // skip the chat-back (used in eval / replay)
}

/**
 * Phrase a one-line confirmation for the resolved reference + intended action,
 * send it in chat, return the phrase (so the planner can log / store it as
 * part of the trajectory for the playbook).
 */
export function confirmAndAct(
  bot: Bot,
  ref: ResolvedReference,
  opts: ConfirmOptions = {}
): string {
  const verb = opts.intent ?? 'on it';
  const phrase = phraseFor(ref, verb);
  if (!opts.silent) {
    bot.chat(phrase);
  }
  return phrase;
}

function phraseFor(ref: ResolvedReference, verb: string): string {
  // Lower-case "verb" if it looks like a verb phrase ("mining", "going to");
  // otherwise treat as free-form trailing fragment.
  const trailing = verb === 'on it' ? 'on it' : `— ${verb}`;
  switch (ref.kind) {
    case 'block':
      return `${capitalize(ref.description)} ${trailing}.`;
    case 'entity':
      return `${capitalize(ref.description)} ${trailing}.`;
    case 'coordinate':
      return `Heading to ${ref.description} ${trailing}.`;
    case 'inventory_item':
      return `Got it — ${ref.description}.`;
  }
}

function capitalize(s: string): string {
  if (s.length === 0) return s;
  return s.charAt(0).toUpperCase() + s.slice(1);
}
