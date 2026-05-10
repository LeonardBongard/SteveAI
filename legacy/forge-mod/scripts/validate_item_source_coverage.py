#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Validate item source coverage thresholds.")
    parser.add_argument(
        "--report",
        default="reports/item_source_coverage.json",
        help="Path to coverage json report",
    )
    parser.add_argument(
        "--min-actionable",
        type=float,
        default=50.0,
        help="Minimum actionable coverage percent required",
    )
    args = parser.parse_args()

    report_path = Path(args.report)
    if not report_path.exists():
        raise SystemExit(f"Coverage report not found: {report_path}")

    data = json.loads(report_path.read_text(encoding="utf-8"))
    actionable = float(data.get("actionable_coverage_percent", 0.0))

    print(json.dumps(data, indent=2))
    if actionable < args.min_actionable:
        raise SystemExit(
            f"FAIL: actionable coverage {actionable:.2f}% < required {args.min_actionable:.2f}%"
        )
    print(f"PASS: actionable coverage {actionable:.2f}% >= required {args.min_actionable:.2f}%")


if __name__ == "__main__":
    main()

