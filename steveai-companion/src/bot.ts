// Bot entry — phase 2.
//
// Connects to a Java server, loads pathfinder, opens the memory store,
// hands every player chat to the planner. The planner owns the control
// loop; this file only handles lifecycle (connect / spawn / chat /
// disconnect) and signal handling.
//
// What's NOT here (intentionally):
// - Tool dispatch, memory composition, retrieval — see planner.ts
// - Memory schemas — see memory/store.ts
// - Tool definitions — see llm/tools.ts, actions/handlers.ts, perception/handlers.ts
// - Eval harness — phase 3.

import 'dotenv/config';
import mineflayer from 'mineflayer';
import pathfinderPkg from 'mineflayer-pathfinder';
const { pathfinder, Movements } = pathfinderPkg;

import { logs } from './log.js';
import { healthCheck, config as llmConfig } from './llm/ollama.js';
import { openMemoryStore, closeMemoryStore } from './memory/store.js';
import { handlePlayerChat } from './planner.js';

// --- Config ---

const MC_HOST = process.env.MC_HOST ?? 'localhost';
const MC_PORT = Number(process.env.MC_PORT ?? '25565');
const MC_USERNAME = process.env.MC_USERNAME ?? 'Steve';
const MC_VERSION =
  !process.env.MC_VERSION || process.env.MC_VERSION === 'false'
    ? undefined
    : process.env.MC_VERSION;
const MC_AUTH = (process.env.MC_AUTH ?? 'offline') as 'offline' | 'microsoft' | 'mojang';

// --- Bot lifecycle ---

async function main(): Promise<void> {
  logs.bot.info({ llm: llmConfig }, 'starting steveai-companion v0.2.0 (phase 2)');

  const health = await healthCheck();
  if (!health.ok) {
    logs.bot.error({ details: health.details }, 'ollama health check failed; exiting');
    process.exit(1);
  }
  logs.bot.info(health.details);

  openMemoryStore();

  const bot = mineflayer.createBot({
    host: MC_HOST,
    port: MC_PORT,
    username: MC_USERNAME,
    auth: MC_AUTH,
    ...(MC_VERSION ? { version: MC_VERSION } : {}),
  });

  bot.loadPlugin(pathfinder);

  bot.once('spawn', () => {
    bot.pathfinder.setMovements(new Movements(bot));
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
    if (username === bot.username) return;
    void handlePlayerChat(bot, username, message).catch((err) =>
      logs.plan.error(
        { err: (err as Error).message, stack: (err as Error).stack },
        'planner crashed'
      )
    );
  });

  bot.on('kicked', (reason) => {
    logs.bot.error({ reason }, 'bot kicked');
  });

  bot.on('error', (err) => {
    logs.bot.error({ err: err.message }, 'mineflayer error');
  });

  bot.on('end', (reason) => {
    logs.bot.warn({ reason }, 'bot disconnected');
    closeMemoryStore();
    process.exit(0);
  });

  process.on('SIGINT', () => {
    logs.bot.info('SIGINT — shutting down');
    closeMemoryStore();
    process.exit(0);
  });
  process.on('SIGTERM', () => {
    logs.bot.info('SIGTERM — shutting down');
    closeMemoryStore();
    process.exit(0);
  });
}

main().catch((err) => {
  logs.bot.fatal({ err: (err as Error).message, stack: (err as Error).stack }, 'fatal');
  closeMemoryStore();
  process.exit(1);
});
