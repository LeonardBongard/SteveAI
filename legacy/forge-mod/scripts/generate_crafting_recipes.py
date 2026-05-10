#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import zipfile
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path


DEFAULT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FORGE_DIR = Path.home() / ".gradle" / "caches" / "forge_gradle" / "minecraft_user_repo" / "net" / "minecraftforge" / "forge"
DEFAULT_VERSION = "1.21.11-61.0.8_mapped_official_1.21.11"
DEFAULT_MC_VERSION = "1.21.11"
DEFAULT_OUTPUT = DEFAULT_ROOT / "src/main/resources/steve/crafting_recipes.csv"


@dataclass(frozen=True)
class RecipeRow:
    output_item_id: str
    output_count: int
    input_item_id: str
    input_count: int
    station: str
    recipe_id: str
    recipe_type: str


def read_zip_text(zf: zipfile.ZipFile, name: str) -> str:
    with zf.open(name) as fh:
        return fh.read().decode("utf-8")


def iter_json_files(zf: zipfile.ZipFile, prefix: str):
    for name in zf.namelist():
        if name.startswith(prefix) and name.endswith(".json"):
            yield name


def find_runtime_jar(version: str, mc_version: str) -> Path:
    vanilla = Path.home() / "Library/Application Support/minecraft/versions" / mc_version / f"{mc_version}.jar"
    if vanilla.exists():
        return vanilla
    base = DEFAULT_FORGE_DIR / version
    forge_runtime = base / f"forge-{version}.jar"
    if forge_runtime.exists():
        return forge_runtime
    raise FileNotFoundError(f"runtime jar not found (checked vanilla={vanilla}, forge={forge_runtime})")


def normalize_token(token: str) -> str:
    token = token.strip()
    if token.startswith("#"):
        return "#" + normalize_token(token[1:])
    if ":" not in token:
        return "minecraft:" + token
    return token


def parse_result(data: dict) -> tuple[str | None, int]:
    result = data.get("result")
    if isinstance(result, str):
        return normalize_token(result), 1
    if isinstance(result, dict):
        item = result.get("item") or result.get("id")
        if isinstance(item, str):
            return normalize_token(item), max(1, int(result.get("count", 1)))
    return None, 1


def load_item_tags(runtime_jar: Path) -> dict[str, set[str]]:
    tags: dict[str, set[str]] = defaultdict(set)
    nested: dict[str, set[str]] = defaultdict(set)
    with zipfile.ZipFile(runtime_jar) as zf:
        for name in zf.namelist():
            if not name.endswith(".json"):
                continue
            parts = name.split("/")
            if len(parts) < 6 or parts[0] != "data" or parts[2] != "tags" or parts[3] not in ("item", "items"):
                continue
            namespace = parts[1]
            path = "/".join(parts[4:]).replace(".json", "")
            data = json.loads(read_zip_text(zf, name))
            tag_name = f"{namespace}:{path}"
            for value in data.get("values", []):
                token = normalize_ingredient_token(value)
                if token.startswith("#"):
                    nested[tag_name].add(token[1:])
                elif token:
                    tags[tag_name].add(token)

    changed = True
    while changed:
        changed = False
        for tag, deps in list(nested.items()):
            for dep in deps:
                before = len(tags[tag])
                tags[tag].update(tags.get(dep, set()))
                if len(tags[tag]) > before:
                    changed = True
    return tags


def normalize_ingredient_token(value) -> str:
    if not isinstance(value, str):
        return ""
    return normalize_token(value)


def parse_ingredient_node(node, item_tags: dict[str, set[str]]) -> list[str]:
    options: set[str] = set()
    if isinstance(node, str):
        token = normalize_ingredient_token(node)
        if token.startswith("#"):
            options.update(item_tags.get(token[1:], set()))
        elif token:
            options.add(token)
        return sorted(options)
    if isinstance(node, list):
        for entry in node:
            options.update(parse_ingredient_node(entry, item_tags))
        return sorted(options)
    if not isinstance(node, dict):
        return []
    item = node.get("item") or node.get("id")
    if isinstance(item, str):
        options.add(normalize_token(item))
    tag = node.get("tag")
    if isinstance(tag, str):
        options.update(item_tags.get(normalize_token(tag), set()))
    return sorted(options)


def pick_canonical_item(node, item_tags: dict[str, set[str]]) -> str | None:
    options = parse_ingredient_node(node, item_tags)
    if not options:
        return None
    return options[0]


def station_for_recipe_type(recipe_type: str) -> str | None:
    if recipe_type in ("minecraft:crafting_shaped", "minecraft:crafting_shapeless"):
        return "CRAFTING_TABLE"
    if recipe_type in (
        "minecraft:smelting",
        "minecraft:blasting",
        "minecraft:smoking",
        "minecraft:campfire_cooking",
    ):
        return "FURNACE"
    if recipe_type == "minecraft:stonecutting":
        return "STONECUTTER"
    return None


def parse_inputs(data: dict, recipe_type: str, item_tags: dict[str, set[str]]) -> dict[str, int]:
    counts: dict[str, int] = defaultdict(int)
    if recipe_type == "minecraft:crafting_shapeless":
        for ing in data.get("ingredients", []):
            item = pick_canonical_item(ing, item_tags)
            if item:
                counts[item] += 1
        return dict(counts)

    if recipe_type == "minecraft:crafting_shaped":
        key = data.get("key", {})
        symbol_map: dict[str, str] = {}
        if isinstance(key, dict):
            for sym, ing in key.items():
                if not isinstance(sym, str) or not sym:
                    continue
                item = pick_canonical_item(ing, item_tags)
                if item:
                    symbol_map[sym[0]] = item
        for line in data.get("pattern", []):
            if not isinstance(line, str):
                continue
            for c in line:
                item = symbol_map.get(c)
                if item:
                    counts[item] += 1
        return dict(counts)

    if recipe_type in (
        "minecraft:smelting",
        "minecraft:blasting",
        "minecraft:smoking",
        "minecraft:campfire_cooking",
        "minecraft:stonecutting",
    ):
        item = pick_canonical_item(data.get("ingredient"), item_tags)
        if item:
            counts[item] = 1
        return dict(counts)

    return {}


def generate_rows(runtime_jar: Path) -> list[RecipeRow]:
    item_tags = load_item_tags(runtime_jar)
    rows: list[RecipeRow] = []
    with zipfile.ZipFile(runtime_jar) as zf:
        for prefix in ("data/minecraft/recipe/", "data/minecraft/recipes/"):
            for name in iter_json_files(zf, prefix):
                data = json.loads(read_zip_text(zf, name))
                recipe_type = str(data.get("type", "")).strip()
                station = station_for_recipe_type(recipe_type)
                if not station:
                    continue
                result_item, output_count = parse_result(data)
                if not result_item:
                    continue
                inputs = parse_inputs(data, recipe_type, item_tags)
                if not inputs:
                    continue
                recipe_id = name.replace(prefix, "").replace(".json", "")
                for input_item, input_count in sorted(inputs.items()):
                    rows.append(
                        RecipeRow(
                            output_item_id=result_item,
                            output_count=max(1, output_count),
                            input_item_id=input_item,
                            input_count=max(1, input_count),
                            station=station,
                            recipe_id=recipe_id,
                            recipe_type=recipe_type,
                        )
                    )
    return sorted(
        rows,
        key=lambda r: (
            r.output_item_id,
            r.station,
            r.recipe_id,
            r.input_item_id,
        ),
    )


def write_rows(path: Path, rows: list[RecipeRow]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(
            [
                "output_item_id",
                "output_count",
                "input_item_id",
                "input_count",
                "station",
                "recipe_id",
                "recipe_type",
            ]
        )
        for r in rows:
            writer.writerow(
                [
                    r.output_item_id,
                    r.output_count,
                    r.input_item_id,
                    r.input_count,
                    r.station,
                    r.recipe_id,
                    r.recipe_type,
                ]
            )


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate station-aware crafting recipe map from Minecraft data.")
    parser.add_argument("--version", default=DEFAULT_VERSION)
    parser.add_argument("--mc-version", default=DEFAULT_MC_VERSION)
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    args = parser.parse_args()

    runtime_jar = find_runtime_jar(args.version, args.mc_version)
    rows = generate_rows(runtime_jar)
    write_rows(Path(args.output), rows)
    print(json.dumps({"runtime_jar": str(runtime_jar), "output": str(args.output), "rows": len(rows)}, indent=2))


if __name__ == "__main__":
    main()
