#!/usr/bin/env bash
set -euo pipefail

# Minimal macOS Minecraft click prototype.
# Behavior: launch app -> wait -> click 4 saved points in order.
# No AX lookup, no process detection, no focus validation.
#
# Saved points directory:
#   ~/.steveai_mc_points_minimal/<name>.point
# Format:
#   X Y
#
# Required point names:
#   play_java
#   singleplayer
#   world_entry
#   world_join
#
# Usage:
#   ./scripts/mc_minimal_click_flow.sh --calibrate play_java
#   ./scripts/mc_minimal_click_flow.sh --calibrate singleplayer
#   ./scripts/mc_minimal_click_flow.sh --calibrate world_entry
#   ./scripts/mc_minimal_click_flow.sh --calibrate world_join
#   ./scripts/mc_minimal_click_flow.sh --run

APP_PATH="/Applications/Minecraft.app"
POINTS_DIR="${HOME}/.steveai_mc_points_minimal"

RUN_FLOW="0"
CALIBRATE_NAME=""
LIST_POINTS="0"

LAUNCHER_WAIT_SECONDS="5"
POST_PLAY_WAIT_SECONDS="10"
STEP_DELAY_SECONDS="1.0"

ensure_points_dir() {
  mkdir -p "${POINTS_DIR}"
}

point_file() {
  local name="$1"
  echo "${POINTS_DIR}/${name}.point"
}

capture_mouse_position() {
  python3 - <<'PY'
import Quartz
evt = Quartz.CGEventCreate(None)
p = Quartz.CGEventGetLocation(evt)
print(f"{int(p.x)} {int(p.y)}")
PY
}

click_abs() {
  local x="$1"
  local y="$2"
  python3 - <<PY
import Quartz
import time
x = int("${x}")
y = int("${y}")
Quartz.CGWarpMouseCursorPosition((x, y))
time.sleep(0.06)
evt_down = Quartz.CGEventCreateMouseEvent(None, Quartz.kCGEventLeftMouseDown, (x, y), Quartz.kCGMouseButtonLeft)
evt_up = Quartz.CGEventCreateMouseEvent(None, Quartz.kCGEventLeftMouseUp, (x, y), Quartz.kCGMouseButtonLeft)
Quartz.CGEventPost(Quartz.kCGHIDEventTap, evt_down)
Quartz.CGEventPost(Quartz.kCGHIDEventTap, evt_up)
print(f"[mc-minimal] Clicked at ({x},{y})")
PY
}

save_point() {
  local name="$1"
  local coords="$2"
  ensure_points_dir
  echo "${coords}" > "$(point_file "${name}")"
  echo "[mc-minimal] Saved '${name}': ${coords}"
}

load_point() {
  local name="$1"
  local f
  f="$(point_file "${name}")"
  if [[ ! -f "${f}" ]]; then
    return 1
  fi
  local coords
  coords="$(cat "${f}")"
  if [[ ! "${coords}" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
    return 1
  fi
  echo "${coords}"
}

click_named_point() {
  local name="$1"
  local coords
  if ! coords="$(load_point "${name}")"; then
    echo "[mc-minimal] Missing/invalid point '${name}'. Calibrate it first." >&2
    exit 1
  fi
  local x="${coords%% *}"
  local y="${coords##* }"
  echo "[mc-minimal] Clicking '${name}'"
  click_abs "${x}" "${y}"
}

list_points() {
  ensure_points_dir
  echo "[mc-minimal] Saved points:"
  local any="0"
  local f
  for f in "${POINTS_DIR}"/*.point; do
    if [[ -f "${f}" ]]; then
      any="1"
      local name
      name="$(basename "${f}" .point)"
      local coords
      coords="$(cat "${f}" 2>/dev/null || echo "invalid")"
      echo "  - ${name}: ${coords}"
    fi
  done
  if [[ "${any}" == "0" ]]; then
    echo "  (none)"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run)
      RUN_FLOW="1"
      shift
      ;;
    --calibrate)
      CALIBRATE_NAME="${2:-}"
      shift 2
      ;;
    --list-points)
      LIST_POINTS="1"
      shift
      ;;
    --launcher-wait)
      LAUNCHER_WAIT_SECONDS="${2:-5}"
      shift 2
      ;;
    --post-play-wait)
      POST_PLAY_WAIT_SECONDS="${2:-10}"
      shift 2
      ;;
    --step-delay)
      STEP_DELAY_SECONDS="${2:-1.0}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ "${LIST_POINTS}" == "1" ]]; then
  list_points
  exit 0
fi

if [[ -n "${CALIBRATE_NAME}" ]]; then
  echo "[mc-minimal] Calibration for '${CALIBRATE_NAME}'"
  echo "[mc-minimal] Move mouse to target and press Enter."
  read -r -p "[mc-minimal] Press Enter to capture..."
  coords="$(capture_mouse_position)"
  if [[ ! "${coords}" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
    echo "[mc-minimal] Failed to capture coordinates." >&2
    exit 1
  fi
  save_point "${CALIBRATE_NAME}" "${coords}"
  exit 0
fi

if [[ "${RUN_FLOW}" != "1" ]]; then
  echo "Nothing to do. Use --run or --calibrate <name>." >&2
  exit 1
fi

if [[ ! -d "${APP_PATH}" ]]; then
  echo "Minecraft app not found: ${APP_PATH}" >&2
  exit 1
fi

echo "[mc-minimal] Launching ${APP_PATH}"
open "${APP_PATH}"

echo "[mc-minimal] Waiting ${LAUNCHER_WAIT_SECONDS}s for launcher"
sleep "${LAUNCHER_WAIT_SECONDS}"

click_named_point "play_java"

echo "[mc-minimal] Waiting ${POST_PLAY_WAIT_SECONDS}s for game menu"
sleep "${POST_PLAY_WAIT_SECONDS}"

click_named_point "singleplayer"
sleep "${STEP_DELAY_SECONDS}"
click_named_point "world_entry"
sleep "${STEP_DELAY_SECONDS}"
click_named_point "world_join"

echo "[mc-minimal] Sequence sent."
exit 0
