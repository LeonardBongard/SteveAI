// Minecraft-knowledge RAG, backed by minecraft-data.
//
// Per docs/COMPANION_V2_DIRECTION.md §3.4: the LLM must NOT hallucinate
// recipes / drops / spawn conditions. Every craft_* skill the LLM writes
// must consult lookupRecipe FIRST. Same for kill_* / hunt_* skills and
// drop expectations.
//
// Data source: the `minecraft-data` npm package (transitive dep). It
// ships per-version JSON for items, recipes, blocks, mobs, drops. We
// build a few thin wrappers + reverse indexes at startup.

import minecraftData from 'minecraft-data';
import { logs } from '../log.js';

export type RecipeView = {
  result: { name: string; count: number };
  ingredients: Array<{ name: string; count: number }>;
  shape?: string[][] | undefined; // 2D pattern of item names (or empty cells)
  station: 'crafting_table' | 'inventory' | 'furnace';
  /** True if this recipe fits Minecraft's 2x2 inventory crafting grid. */
  inventoryCraftable: boolean;
};

export type MobView = {
  name: string;
  displayName: string;
  type?: string;
  drops: Array<{
    item: string;
    chance: number;
    countRange: [number, number];
    playerKill?: boolean;
  }>;
};

export type BlockView = {
  name: string;
  displayName: string;
  hardness: number | null;
  diggable: boolean;
  harvestTools: string[]; // item names
  drops: string[]; // item names
  stackSize: number | null;
  emitsLight?: number;
  transparent?: boolean;
};

interface KnowledgeBase {
  version: string;
  recipesByItem: Map<string, RecipeView[]>;
  recipesUsing: Map<string, RecipeView[]>; // ingredient name → recipes that use it
  mobsByName: Map<string, MobView>;
  mobsDropping: Map<string, MobView[]>; // dropped item → mobs that drop it
  blocksByName: Map<string, BlockView>;
  /** Set of canonical item names known to the connected MC version. Used for
   *  skill-code lint to catch typo'd `bot.registry.itemsByName.X` references. */
  itemNames: Set<string>;
}

let kb: KnowledgeBase | null = null;

export function loadKnowledge(version = '1.21.11'): KnowledgeBase {
  if (kb && kb.version === version) return kb;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const md: any = minecraftData(version);
  if (!md || !md.itemsByName || !md.recipes) {
    throw new Error(`minecraft-data has no data for version ${version}`);
  }

  const recipesByItem = new Map<string, RecipeView[]>();
  const recipesUsing = new Map<string, RecipeView[]>();
  const mobsByName = new Map<string, MobView>();
  const mobsDropping = new Map<string, MobView[]>();
  const blocksByName = new Map<string, BlockView>();
  const itemNames = new Set<string>();

  // --- Recipes (crafting + smelting) ---
  buildRecipeIndex(md, recipesByItem, recipesUsing);

  // --- Mobs + drops ---
  buildMobIndex(md, mobsByName, mobsDropping);

  // --- Blocks ---
  buildBlockIndex(md, blocksByName);

  // --- Item registry (names only — used for skill-code lint) ---
  for (const item of md.itemsArray ?? []) {
    if (item?.name) itemNames.add(item.name);
  }

  kb = { version, recipesByItem, recipesUsing, mobsByName, mobsDropping, blocksByName, itemNames };
  logs.bot.info(
    {
      mcVersion: version,
      recipes: recipesByItem.size,
      mobs: mobsByName.size,
      blocks: blocksByName.size,
    },
    'knowledge base loaded'
  );
  return kb;
}

// ============================================================================
// Lookup tools (these become LLM tools via planner.ts dispatch)
// ============================================================================

export function lookupRecipe(itemName: string): RecipeView[] {
  return getKb().recipesByItem.get(canon(itemName)) ?? [];
}

export function lookupMob(name: string): MobView | null {
  return getKb().mobsByName.get(canon(name)) ?? null;
}

export function lookupBlock(name: string): BlockView | null {
  return getKb().blocksByName.get(canon(name)) ?? null;
}

export function findRecipesContaining(ingredientName: string): RecipeView[] {
  return getKb().recipesUsing.get(canon(ingredientName)) ?? [];
}

export function findMobsDropping(itemName: string): MobView[] {
  return getKb().mobsDropping.get(canon(itemName)) ?? [];
}

// ============================================================================
// Registry validation (used by skill-code lint at writeSkill time)
// ============================================================================

export function isKnownItem(name: string): boolean {
  return getKb().itemNames.has(canon(name));
}

export function isKnownBlock(name: string): boolean {
  return getKb().blocksByName.has(canon(name));
}

/**
 * Suggest a similar known name when the LLM hallucinates an identifier
 * (most commonly singular-vs-plural — e.g. `oak_plank` when it should be
 * `oak_planks`). Tries simple toggles first, then a top-3 prefix search.
 * Returns null if no plausible suggestion.
 */
export function suggestSimilar(name: string, kind: 'item' | 'block'): string | null {
  const c = canon(name);
  const pool = kind === 'item' ? getKb().itemNames : new Set(getKb().blocksByName.keys());

  // 1. Plural toggle: foo → foos, foos → foo, foe → foes/foeses (etc.)
  const toggles = [c + 's', c + 'es'];
  if (c.endsWith('s')) toggles.push(c.slice(0, -1));
  if (c.endsWith('es')) toggles.push(c.slice(0, -2));
  for (const t of toggles) {
    if (pool.has(t)) return t;
  }

  // 2. Prefix overlap (≥4-char common prefix) — catches things like
  //    "wooden_plank" → "wooden_planks", "oak_wood" → "oak_log" etc.
  const minPrefix = Math.min(c.length - 1, 4);
  const candidates: string[] = [];
  for (const cand of pool) {
    if (cand.length < minPrefix) continue;
    if (cand.startsWith(c.slice(0, minPrefix)) || c.startsWith(cand.slice(0, minPrefix))) {
      candidates.push(cand);
    }
  }
  candidates.sort((a, b) => Math.abs(a.length - c.length) - Math.abs(b.length - c.length));
  return candidates[0] ?? null;
}

// ============================================================================
// Internals
// ============================================================================

function getKb(): KnowledgeBase {
  if (!kb) throw new Error('knowledge base not loaded; call loadKnowledge() first');
  return kb;
}

function canon(name: string): string {
  return name.trim().toLowerCase().replace(/^minecraft:/, '');
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function buildRecipeIndex(md: any, byItem: Map<string, RecipeView[]>, using: Map<string, RecipeView[]>): void {
  const itemNameById = new Map<number, string>();
  for (const item of md.itemsArray) itemNameById.set(item.id, item.name);

  for (const itemIdStr of Object.keys(md.recipes ?? {})) {
    const id = Number(itemIdStr);
    const itemName = itemNameById.get(id);
    if (!itemName) continue;
    const variants = md.recipes[id];
    if (!Array.isArray(variants)) continue;
    for (const variant of variants) {
      const recipe = normalizeRecipe(variant, itemName, itemNameById);
      if (!recipe) continue;
      pushList(byItem, itemName, recipe);
      for (const ing of recipe.ingredients) pushList(using, ing.name, recipe);
    }
  }
}

interface RawRecipe {
  result: { id: number; count: number } | number;
  inShape?: (number | null)[][];
  ingredients?: number[];
}

function normalizeRecipe(
  variant: RawRecipe,
  resultName: string,
  itemNameById: Map<number, string>
): RecipeView | null {
  const resultCount = typeof variant.result === 'number' ? 1 : (variant.result?.count ?? 1);

  // Tally ingredients from either inShape (2D pattern) or ingredients (flat list).
  const counts = new Map<string, number>();
  const shape: string[][] = [];

  if (variant.inShape) {
    for (const row of variant.inShape) {
      const sRow: string[] = [];
      for (const cell of row) {
        if (cell === null || cell === undefined) {
          sRow.push('');
          continue;
        }
        const name = itemNameById.get(cell as number) ?? '';
        if (name) {
          sRow.push(name);
          counts.set(name, (counts.get(name) ?? 0) + 1);
        } else {
          sRow.push('');
        }
      }
      shape.push(sRow);
    }
  } else if (Array.isArray(variant.ingredients)) {
    for (const id of variant.ingredients) {
      const name = itemNameById.get(id);
      if (!name) continue;
      counts.set(name, (counts.get(name) ?? 0) + 1);
    }
  } else {
    return null;
  }

  const ingredients = [...counts.entries()].map(([name, count]) => ({ name, count }));
  const inventoryCraftable = fitsInInventoryGrid(shape, ingredients);
  const recipe: RecipeView = {
    result: { name: resultName, count: resultCount },
    ingredients,
    station: inventoryCraftable ? 'inventory' : 'crafting_table',
    inventoryCraftable,
  };
  if (shape.length > 0) recipe.shape = shape;
  return recipe;
}

/**
 * Minecraft rule: the 2x2 inventory crafting grid accepts any recipe whose
 * pattern fits within 2x2. Shapeless recipes with ≤ 4 ingredient slots
 * (≤ 4 total items) also fit. Anything larger requires a crafting_table.
 *
 * This is the FIX for the v2-playtest "crafting-table fixation" bug:
 * previously every recipe was marked `crafting_table`, leading the LLM into
 * circular reasoning ("I need a crafting_table to craft a crafting_table").
 */
function fitsInInventoryGrid(
  shape: string[][],
  ingredients: Array<{ name: string; count: number }>
): boolean {
  // Shaped recipe: explicit grid dimensions.
  if (shape.length > 0) {
    const rows = shape.length;
    const cols = Math.max(...shape.map((r) => r.length));
    return rows <= 2 && cols <= 2;
  }
  // Shapeless recipe: just count total ingredient units. ≤4 fits 2x2.
  const total = ingredients.reduce((s, i) => s + i.count, 0);
  return total <= 4;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function buildMobIndex(md: any, byName: Map<string, MobView>, dropping: Map<string, MobView[]>): void {
  const entityLoot = md.entityLoot ?? {};
  const entityNames = Object.keys(entityLoot);

  for (const name of entityNames) {
    const lootEntry = entityLoot[name];
    const entity = md.entitiesByName?.[name];
    const drops: MobView['drops'] = [];
    if (Array.isArray(lootEntry?.drops)) {
      for (const d of lootEntry.drops) {
        if (!d?.item) continue;
        drops.push({
          item: canon(d.item),
          chance: typeof d.dropChance === 'number' ? d.dropChance : 1,
          countRange: Array.isArray(d.stackSizeRange)
            ? [Number(d.stackSizeRange[0] ?? 1), Number(d.stackSizeRange[1] ?? 1)]
            : [1, 1],
          ...(d.playerKill ? { playerKill: true } : {}),
        });
      }
    }
    const view: MobView = {
      name: canon(name),
      displayName: entity?.displayName ?? name,
      ...(entity?.type ? { type: entity.type } : {}),
      drops,
    };
    byName.set(view.name, view);
    for (const d of drops) {
      pushList(dropping, d.item, view);
    }
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function buildBlockIndex(md: any, byName: Map<string, BlockView>): void {
  const itemNameById = new Map<number, string>();
  for (const item of md.itemsArray ?? []) itemNameById.set(item.id, item.name);

  for (const block of md.blocksArray ?? []) {
    const harvestTools: string[] = [];
    if (block.harvestTools && typeof block.harvestTools === 'object') {
      for (const idStr of Object.keys(block.harvestTools)) {
        const name = itemNameById.get(Number(idStr));
        if (name) harvestTools.push(name);
      }
    }
    const drops: string[] = Array.isArray(block.drops)
      ? block.drops
          .map((d: number) => itemNameById.get(d))
          .filter((s: string | undefined): s is string => Boolean(s))
      : [];

    const view: BlockView = {
      name: block.name,
      displayName: block.displayName ?? block.name,
      hardness: typeof block.hardness === 'number' ? block.hardness : null,
      diggable: block.diggable !== false,
      harvestTools,
      drops,
      stackSize: typeof block.stackSize === 'number' ? block.stackSize : null,
      ...(typeof block.emitLight === 'number' ? { emitsLight: block.emitLight } : {}),
      ...(typeof block.transparent === 'boolean' ? { transparent: block.transparent } : {}),
    };
    byName.set(view.name, view);
  }
}

function pushList<K, V>(m: Map<K, V[]>, k: K, v: V): void {
  const arr = m.get(k);
  if (arr) arr.push(v);
  else m.set(k, [v]);
}
