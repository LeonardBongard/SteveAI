#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import re
import zipfile
from collections import defaultdict
from pathlib import Path


DEFAULT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FORGE_DIR = Path.home() / ".gradle" / "caches" / "forge_gradle" / "minecraft_user_repo" / "net" / "minecraftforge" / "forge"
DEFAULT_VERSION = "1.21.11-61.0.8_mapped_official_1.21.11"


ITEM_CONST_RE = re.compile(r"public\s+static\s+final\s+Item\s+([A-Z0-9_]+)\s*=")
NS_ID_RE = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_/.-]+$")


def to_item_id(const_name: str) -> str:
    return f"minecraft:{const_name.lower()}"


def find_jars(version: str) -> tuple[Path, Path]:
    base = DEFAULT_FORGE_DIR / version
    src = base / f"forge-{version}-sources.jar"
    runtime = base / f"forge-{version}.jar"
    if not src.exists():
        raise FileNotFoundError(f"sources jar not found: {src}")
    if not runtime.exists():
        raise FileNotFoundError(f"runtime jar not found: {runtime}")
    return src, runtime


def read_zip_text(zf: zipfile.ZipFile, name: str) -> str:
    with zf.open(name) as fh:
        return fh.read().decode("utf-8")


def load_all_items(source_jar: Path) -> list[str]:
    with zipfile.ZipFile(source_jar) as zf:
        items_java = read_zip_text(zf, "net/minecraft/world/item/Items.java")
    ids = []
    for m in ITEM_CONST_RE.finditer(items_java):
        ids.append(to_item_id(m.group(1)))
    return sorted(set(ids))


def iter_json_files(zf: zipfile.ZipFile, prefix: str):
    for name in zf.namelist():
        if name.startswith(prefix) and name.endswith(".json"):
            yield name


def load_item_tags(runtime_jar: Path) -> dict[str, set[str]]:
    tags: dict[str, set[str]] = defaultdict(set)
    nested: dict[str, set[str]] = defaultdict(set)
    with zipfile.ZipFile(runtime_jar) as zf:
        for name in zf.namelist():
            if not name.endswith(".json"):
                continue
            parts = name.split("/")
            # data/<namespace>/tags/item/<path>.json or tags/items
            if len(parts) < 6 or parts[0] != "data" or parts[2] != "tags" or parts[3] not in ("item", "items"):
                continue
            namespace = parts[1]
            path = "/".join(parts[4:]).replace(".json", "")
            data = json.loads(read_zip_text(zf, name))
            tag_name = f"{namespace}:{path}"
            for value in data.get("values", []):
                for resolved in normalize_ingredient_token(value):
                    if resolved.startswith("#"):
                        nested[tag_name].add(resolved[1:])
                    elif NS_ID_RE.match(resolved):
                        tags[tag_name].add(resolved)
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


def parse_ingredient(ing, item_tags: dict[str, set[str]]) -> set[str]:
    out: set[str] = set()
    if isinstance(ing, str):
        token = normalize_ingredient_token(ing)
        if token.startswith("#"):
            out.update(item_tags.get(token[1:], set()))
        elif NS_ID_RE.match(token):
            out.add(token)
        return out
    if isinstance(ing, list):
        for e in ing:
            out.update(parse_ingredient(e, item_tags))
        return out
    if not isinstance(ing, dict):
        return out
    for key in ("item", "id"):
        item = ing.get(key)
        if isinstance(item, str):
            normalized = normalize_token(item)
            if NS_ID_RE.match(normalized):
                out.add(normalized)
    tag = ing.get("tag")
    if isinstance(tag, str):
        normalized_tag = normalize_token(tag)
        out.update(item_tags.get(normalized_tag, set()))
    return out


def parse_recipe_result(data: dict) -> tuple[str | None, int]:
    result = data.get("result")
    if isinstance(result, str):
        normalized = normalize_token(result)
        return normalized if NS_ID_RE.match(normalized) else None, 1
    if isinstance(result, dict):
        item = result.get("item") or result.get("id")
        count = int(result.get("count", 1))
        if isinstance(item, str):
            normalized = normalize_token(item)
            if NS_ID_RE.match(normalized):
                return normalized, max(count, 1)
    return None, 1


def load_recipe_sources(runtime_jar: Path, item_tags: dict[str, set[str]]) -> dict[str, set[str]]:
    sources: dict[str, set[str]] = defaultdict(set)
    with zipfile.ZipFile(runtime_jar) as zf:
        for name in iter_json_files(zf, "data/minecraft/recipe/"):
            data = json.loads(read_zip_text(zf, name))
            result_item, _ = parse_recipe_result(data)
            if not result_item:
                continue
            recipe_type = data.get("type", "")
            ingredient_items: set[str] = set()
            if isinstance(data.get("ingredients"), list):
                for ing in data["ingredients"]:
                    ingredient_items.update(parse_ingredient(ing, item_tags))
            if isinstance(data.get("ingredients"), dict):
                ingredient_items.update(parse_ingredient(data["ingredients"], item_tags))
            if isinstance(data.get("ingredients"), str):
                ingredient_items.update(parse_ingredient(data["ingredients"], item_tags))
            if isinstance(data.get("ingredient"), dict):
                ingredient_items.update(parse_ingredient(data["ingredient"], item_tags))
            if isinstance(data.get("ingredient"), str):
                ingredient_items.update(parse_ingredient(data["ingredient"], item_tags))
            if isinstance(data.get("base"), (dict, list, str)):
                ingredient_items.update(parse_ingredient(data["base"], item_tags))
            if isinstance(data.get("addition"), (dict, list, str)):
                ingredient_items.update(parse_ingredient(data["addition"], item_tags))
            if isinstance(data.get("template"), (dict, list, str)):
                ingredient_items.update(parse_ingredient(data["template"], item_tags))
            if isinstance(data.get("key"), dict):
                for v in data["key"].values():
                    ingredient_items.update(parse_ingredient(v, item_tags))
            if recipe_type.startswith("minecraft:smithing"):
                for part in ("template", "base", "addition"):
                    if part in data:
                        ingredient_items.update(parse_ingredient(data[part], item_tags))
            if not ingredient_items:
                continue
            for ing in ingredient_items:
                sources[result_item].add(ing)
    return sources


def recursive_item_names_for_loot(node, parent_type: str | None = None) -> set[str]:
    found: set[str] = set()
    if isinstance(node, dict):
        node_type = node.get("type", parent_type)
        for k, v in node.items():
            if k == "name" and isinstance(v, str) and NS_ID_RE.match(v):
                if isinstance(node_type, str) and node_type.endswith(":item"):
                    found.add(v)
            else:
                found.update(recursive_item_names_for_loot(v, node_type))
    elif isinstance(node, list):
        for e in node:
            found.update(recursive_item_names_for_loot(e, parent_type))
    return found


def load_loot_sources(runtime_jar: Path) -> dict[str, set[str]]:
    sources: dict[str, set[str]] = defaultdict(set)
    with zipfile.ZipFile(runtime_jar) as zf:
        for name in iter_json_files(zf, "data/minecraft/loot_table/blocks/"):
            block_path = name.split("/")[-1].replace(".json", "")
            source_block = f"minecraft:{block_path}"
            data = json.loads(read_zip_text(zf, name))
            drops = recursive_item_names_for_loot(data)
            for item in drops:
                sources[item].add(source_block)
    return sources


def load_overrides(path: Path) -> dict[str, set[str]]:
    out: dict[str, set[str]] = defaultdict(set)
    if not path.exists():
        return out
    with path.open(newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            target = (row.get("target_item_id") or "").strip()
            source = (row.get("source_id") or "").strip()
            if NS_ID_RE.match(target) and NS_ID_RE.match(source):
                out[target].add(source)
    return out


def write_sources(path: Path, merged: dict[str, set[str]]):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["target_item_id", "source_id", "method", "confidence"])
        for target in sorted(merged.keys()):
            for source in sorted(merged[target]):
                method = "auto"
                confidence = "medium"
                if target == source:
                    method = "identity"
                    confidence = "high"
                w.writerow([target, source, method, confidence])


def write_all_items(path: Path, all_items: list[str]):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["item_id"])
        for item in all_items:
            w.writerow([item])


def write_coverage(
    report_json: Path,
    unmapped_csv: Path,
    all_items: list[str],
    merged: dict[str, set[str]],
):
    mapped = [i for i in all_items if merged.get(i)]
    unmapped = [i for i in all_items if not merged.get(i)]
    coverage = (len(mapped) / len(all_items) * 100.0) if all_items else 0.0

    report_json.parent.mkdir(parents=True, exist_ok=True)
    actionable_mapped = [i for i in all_items if any(src != i for src in merged.get(i, set()))]
    actionable_unmapped = [i for i in all_items if not any(src != i for src in merged.get(i, set()))]
    actionable_coverage = (len(actionable_mapped) / len(all_items) * 100.0) if all_items else 0.0

    report = {
        "total_items": len(all_items),
        "mapped_items": len(mapped),
        "unmapped_items": len(unmapped),
        "coverage_percent": round(coverage, 2),
        "actionable_mapped_items": len(actionable_mapped),
        "actionable_unmapped_items": len(actionable_unmapped),
        "actionable_coverage_percent": round(actionable_coverage, 2),
    }
    report_json.write_text(json.dumps(report, indent=2), encoding="utf-8")

    unmapped_csv.parent.mkdir(parents=True, exist_ok=True)
    with unmapped_csv.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["item_id", "reason"])
        for item in actionable_unmapped:
            w.writerow([item, "identity_only"])
        for item in unmapped:
            if item in actionable_unmapped:
                continue
            w.writerow([item, "unmapped"])

    return report


def main():
    parser = argparse.ArgumentParser(description="Generate item source mapping and coverage reports.")
    parser.add_argument("--version", default=DEFAULT_VERSION)
    parser.add_argument("--output-sources", default=str(DEFAULT_ROOT / "src/main/resources/steve/item_sources.csv"))
    parser.add_argument("--output-all-items", default=str(DEFAULT_ROOT / "generated/all_items.csv"))
    parser.add_argument("--output-report", default=str(DEFAULT_ROOT / "reports/item_source_coverage.json"))
    parser.add_argument("--output-unmapped", default=str(DEFAULT_ROOT / "reports/unmapped_items.csv"))
    parser.add_argument("--overrides", default=str(DEFAULT_ROOT / "src/main/resources/steve/item_sources_overrides.csv"))
    args = parser.parse_args()

    source_jar, runtime_jar = find_jars(args.version)
    all_items = load_all_items(source_jar)
    item_tags = load_item_tags(runtime_jar)
    recipe_sources = load_recipe_sources(runtime_jar, item_tags)
    loot_sources = load_loot_sources(runtime_jar)
    override_sources = load_overrides(Path(args.overrides))

    merged: dict[str, set[str]] = defaultdict(set)
    for item in all_items:
        merged[item].add(item)
    for d in (recipe_sources, loot_sources, override_sources):
        for target, srcs in d.items():
            merged[target].update(srcs)

    write_sources(Path(args.output_sources), merged)
    write_all_items(Path(args.output_all_items), all_items)
    report = write_coverage(
        Path(args.output_report),
        Path(args.output_unmapped),
        all_items,
        merged,
    )
    print(json.dumps(report, indent=2))


def normalize_token(token: str) -> str:
    token = token.strip()
    if token.startswith("#"):
        return "#" + normalize_token(token[1:])
    if ":" not in token:
        return "minecraft:" + token
    return token


def normalize_ingredient_token(value) -> str:
    if not isinstance(value, str):
        return ""
    return normalize_token(value)


if __name__ == "__main__":
    main()
