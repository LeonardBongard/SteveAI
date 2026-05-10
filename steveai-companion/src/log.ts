import pino from 'pino';

const level = process.env.LOG_LEVEL ?? 'info';

const isProd = process.env.NODE_ENV === 'production';

export const log = pino({
  level,
  timestamp: pino.stdTimeFunctions.isoTime,
  ...(isProd
    ? {}
    : {
        transport: {
          target: 'pino-pretty',
          options: {
            colorize: true,
            translateTime: 'HH:MM:ss.l',
            ignore: 'pid,hostname',
          },
        },
      }),
});

// Tagged child loggers — keeps the [TAG] prefix discipline from the Forge mod
// (see docs/LOGGING_POLICY.md). Use `import { logs } from './log.js'`
// then `logs.plan.info(...)` etc.
export const logs = {
  bot: log.child({ tag: 'BOT' }),
  plan: log.child({ tag: 'PLAN' }),
  act: log.child({ tag: 'ACT' }),
  mem: log.child({ tag: 'MEM' }),
  ground: log.child({ tag: 'GROUND' }),
  llm: log.child({ tag: 'LLM' }),
  eval: log.child({ tag: 'EVAL' }),
};
