#!/usr/bin/env bash
set -euo pipefail

# macOS helper to launch Minecraft, click launcher Play, then optionally click
# saved in-game menu points to start a world.
#
# Requires:
# - Accessibility permission for Terminal/Codex app (for AX scripting fallback)
# - Minecraft installed at /Applications/Minecraft.app
#
# Point storage:
#   ~/.steveai_mc_points/<name>.point
# Formats:
#   REL <process> <dx> <dy>
#   ABS <x> <y>
#   <x> <y> (legacy; treated as ABS)
#
# Usage examples:
#   ./scripts/mc_autostart_mac.sh --calibrate-point play_java
#   ./scripts/mc_autostart_mac.sh --calibrate-point singleplayer
#   ./scripts/mc_autostart_mac.sh --calibrate-point world_entry
#   ./scripts/mc_autostart_mac.sh --calibrate-point world_join
#   ./scripts/mc_autostart_mac.sh --run-world-sequence
#   ./scripts/mc_autostart_mac.sh --run-world-sequence --post-play-delay 10 --sequence-delay 1.0
#   ./scripts/mc_autostart_mac.sh --list-points

APP_PATH="/Applications/Minecraft.app"
WAIT_LAUNCHER_SECONDS="10"
POST_PLAY_DELAY_SECONDS="10"
CLICK_PLAY="1"

POINTS_DIR="${HOME}/.steveai_mc_points"
CALIBRATE_POINT=""
MOVE_TO_POINT=""
CLICK_POINT=""
LIST_POINTS="0"
RUN_WORLD_SEQUENCE="0"
SEQUENCE_DELAY_SECONDS="1.0"
SEQUENCE_RETRIES="2"

# Default world-start sequence:
# - Click Singleplayer button
# - Click target world entry
# - Click "Play Selected World"
WORLD_SEQUENCE=("singleplayer" "world_entry" "world_join")

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

resolve_point_process() {
  local name="$1"
  case "${name}" in
    play_java)
      echo "launcher"
      ;;
    *)
      # In-game menu points are in the Java client window.
      echo "java"
      ;;
  esac
}

get_process_window_origin() {
  local proc="$1"
  osascript <<APPLESCRIPT
tell application "System Events"
  if not (exists process "${proc}") then return ""
  tell process "${proc}"
    if not (exists window 1) then return ""
    set b to bounds of window 1
    return ((item 1 of b as integer) as text) & " " & ((item 2 of b as integer) as text)
  end tell
end tell
APPLESCRIPT
}

move_mouse_abs() {
  local x="$1"
  local y="$2"
  python3 - <<PY
import Quartz
x = int("${x}")
y = int("${y}")
Quartz.CGWarpMouseCursorPosition((x, y))
print(f"[mc-autostart] Mouse moved to ({x},{y})")
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
print(f"[mc-autostart] Clicked at ({x},{y})")
PY
}

save_point_raw() {
  local name="$1"
  local line="$2"
  ensure_points_dir
  echo "${line}" > "$(point_file "${name}")"
  echo "[mc-autostart] Saved point '${name}': ${line}"
}

load_point_line() {
  local name="$1"
  local f
  f="$(point_file "${name}")"
  if [[ ! -f "${f}" ]]; then
    return 1
  fi
  cat "${f}"
}

resolve_point_to_abs() {
  local name="$1"
  local line
  if ! line="$(load_point_line "${name}")"; then
    return 1
  fi

  # Legacy format: "x y"
  if [[ "${line}" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
    echo "${line}"
    return 0
  fi

  # ABS format
  if [[ "${line}" =~ ^ABS[[:space:]]+([0-9]+)[[:space:]]+([0-9]+)$ ]]; then
    echo "${BASH_REMATCH[1]} ${BASH_REMATCH[2]}"
    return 0
  fi

  # REL format
  if [[ "${line}" =~ ^REL[[:space:]]+([[:alnum:]_.-]+)[[:space:]]+(-?[0-9]+)[[:space:]]+(-?[0-9]+)$ ]]; then
    local proc="${BASH_REMATCH[1]}"
    local dx="${BASH_REMATCH[2]}"
    local dy="${BASH_REMATCH[3]}"
    local origin
    origin="$(get_process_window_origin "${proc}" | tr -d '\r' | tail -n 1)"
    if [[ ! "${origin}" =~ ^-?[0-9]+[[:space:]]+-?[0-9]+$ ]]; then
      return 1
    fi
    local ox="${origin%% *}"
    local oy="${origin##* }"
    echo "$((ox + dx)) $((oy + dy))"
    return 0
  fi

  return 1
}

capture_point_line() {
  local name="$1"
  local cursor
  cursor="$(capture_mouse_position)"
  if [[ ! "${cursor}" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
    return 1
  fi
  local cx="${cursor%% *}"
  local cy="${cursor##* }"

  local proc
  proc="$(resolve_point_process "${name}")"
  local origin
  origin="$(get_process_window_origin "${proc}" | tr -d '\r' | tail -n 1)"
  if [[ "${origin}" =~ ^-?[0-9]+[[:space:]]+-?[0-9]+$ ]]; then
    local ox="${origin%% *}"
    local oy="${origin##* }"
    local dx="$((cx - ox))"
    local dy="$((cy - oy))"
    echo "REL ${proc} ${dx} ${dy}"
  else
    echo "ABS ${cx} ${cy}"
  fi
}

list_points() {
  ensure_points_dir
  echo "[mc-autostart] Saved points:"
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

activate_process_frontmost() {
  local proc="$1"
  osascript <<APPLESCRIPT
tell application "System Events"
  if exists process "${proc}" then
    tell process "${proc}"
      set frontmost to true
    end tell
  end if
end tell
APPLESCRIPT
}

is_process_frontmost() {
  local proc="$1"
  osascript <<APPLESCRIPT
tell application "System Events"
  if not (exists process "${proc}") then return "0"
  tell process "${proc}"
    if frontmost then
      return "1"
    else
      return "0"
    end if
  end tell
end tell
APPLESCRIPT
}

focus_process_with_retry() {
  local proc="$1"
  local attempts="${2:-8}"
  local i
  for ((i=0; i<attempts; i++)); do
    activate_process_frontmost "${proc}" || true
    if [[ "$(is_process_frontmost "${proc}" | tr -d '\r' | tail -n 1)" == "1" ]]; then
      return 0
    fi
    sleep 0.15
  done
  return 1
}

click_named_point() {
  local name="$1"
  local coords
  if ! coords="$(resolve_point_to_abs "${name}")"; then
    echo "[mc-autostart] Missing or invalid point: '${name}'" >&2
    return 1
  fi
  local x="${coords%% *}"
  local y="${coords##* }"
  echo "[mc-autostart] Clicking saved point '${name}'"
  click_abs "${x}" "${y}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app)
      APP_PATH="${2:-}"
      shift 2
      ;;
    --wait-launcher)
      WAIT_LAUNCHER_SECONDS="${2:-10}"
      shift 2
      ;;
    --wait-client|--post-play-delay)
      POST_PLAY_DELAY_SECONDS="${2:-10}"
      shift 2
      ;;
    --no-click-play)
      CLICK_PLAY="0"
      shift
      ;;
    --calibrate-point)
      CALIBRATE_POINT="${2:-}"
      shift 2
      ;;
    --move-to-point)
      MOVE_TO_POINT="${2:-}"
      shift 2
      ;;
    --click-point)
      CLICK_POINT="${2:-}"
      shift 2
      ;;
    --list-points)
      LIST_POINTS="1"
      shift
      ;;
    --run-world-sequence)
      RUN_WORLD_SEQUENCE="1"
      shift
      ;;
    --sequence-delay)
      SEQUENCE_DELAY_SECONDS="${2:-1.0}"
      shift 2
      ;;
    --sequence-retries)
      SEQUENCE_RETRIES="${2:-2}"
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

if [[ -n "${CALIBRATE_POINT}" ]]; then
  echo "[mc-autostart] Calibration mode for point '${CALIBRATE_POINT}'"
  echo "[mc-autostart] Move mouse to target position and press Enter."
  read -r -p "[mc-autostart] Press Enter to capture cursor position..."
  if ! coords="$(capture_point_line "${CALIBRATE_POINT}")"; then
    echo "[mc-autostart] Failed to capture coordinates." >&2
    exit 1
  fi
  save_point_raw "${CALIBRATE_POINT}" "${coords}"
  exit 0
fi

if [[ -n "${MOVE_TO_POINT}" ]]; then
  if ! coords="$(resolve_point_to_abs "${MOVE_TO_POINT}")"; then
    echo "[mc-autostart] Missing or invalid point: '${MOVE_TO_POINT}'" >&2
    exit 1
  fi
  x="${coords%% *}"
  y="${coords##* }"
  move_mouse_abs "${x}" "${y}"
  exit 0
fi

if [[ -n "${CLICK_POINT}" ]]; then
  click_named_point "${CLICK_POINT}"
  exit 0
fi

if [[ ! -d "${APP_PATH}" ]]; then
  echo "Minecraft app not found at: $APP_PATH" >&2
  exit 1
fi

if ! command -v osascript >/dev/null 2>&1; then
  echo "osascript not available on this system." >&2
  exit 1
fi

echo "[mc-autostart] Launching ${APP_PATH}"
open "${APP_PATH}"

echo "[mc-autostart] Waiting for launcher process..."
deadline=$((SECONDS + WAIT_LAUNCHER_SECONDS))
while (( SECONDS < deadline )); do
  if pgrep -f "/Applications/Minecraft.app/Contents/MacOS/launcher" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! pgrep -f "/Applications/Minecraft.app/Contents/MacOS/launcher" >/dev/null 2>&1; then
  echo "[mc-autostart] Launcher process not detected in time." >&2
  exit 1
fi

echo "[mc-autostart] Focusing launcher window"
osascript <<'APPLESCRIPT'
tell application "Minecraft" to activate
tell application "System Events"
  if exists process "launcher" then
    tell process "launcher"
      set frontmost to true
    end tell
  end if
end tell
APPLESCRIPT

if [[ "${CLICK_PLAY}" == "1" ]]; then
  echo "[mc-autostart] Clicking launcher Play button (AX lookup)"
  click_error_file="$(mktemp)"
  if click_result="$(osascript 2>"${click_error_file}" <<'APPLESCRIPT'
tell application "System Events"
  if not (exists process "launcher") then return "missing-process"
  tell process "launcher"
    set frontmost to true
    delay 0.2
    if not (exists window 1) then return "missing-window"
    tell window 1
      set allElems to entire contents
      repeat with e in allElems
        try
          if role of e is "AXButton" then
            set btnName to ""
            try
              set btnName to name of e as text
            end try
            if btnName contains "Play" then
              click e
              return "clicked-ax-play"
            end if
          end if
        end try
      end repeat
    end tell
  end tell
end tell
return "not-found"
APPLESCRIPT
  )"; then
    echo "[mc-autostart] Play click result: ${click_result}"
  else
    click_result="ax-click-error"
    echo "[mc-autostart] Play click failed via AX automation."
    echo "[mc-autostart] macOS error: $(tr -d '\n' < "${click_error_file}")"
    echo "[mc-autostart] Grant Accessibility for your terminal/Codex app in System Settings -> Privacy & Security -> Accessibility."
  fi
  rm -f "${click_error_file}"

  if [[ "${click_result}" != "clicked-ax-play" ]]; then
    if resolve_point_to_abs "play_java" >/dev/null; then
      click_named_point "play_java"
    else
      echo "[mc-autostart] No fallback point 'play_java' found. Calibrate it with:"
      echo "  ./scripts/mc_autostart_mac.sh --calibrate-point play_java"
    fi
  fi
fi

if [[ "${RUN_WORLD_SEQUENCE}" == "1" ]]; then
  echo "[mc-autostart] Waiting ${POST_PLAY_DELAY_SECONDS}s after Play click..."
  sleep "${POST_PLAY_DELAY_SECONDS}"
  echo "[mc-autostart] Running world-start sequence: ${WORLD_SEQUENCE[*]}"
  if ! focus_process_with_retry "java" 20; then
    echo "[mc-autostart] Warning: could not confirm java window focus; continuing."
  fi
  sleep 0.2
  for point_name in "${WORLD_SEQUENCE[@]}"; do
    if ! focus_process_with_retry "java" 8; then
      echo "[mc-autostart] Warning: java not focused before '${point_name}' click."
    fi
    click_count=0
    while (( click_count < SEQUENCE_RETRIES )); do
      click_named_point "${point_name}"
      click_count=$((click_count + 1))
      if (( click_count < SEQUENCE_RETRIES )); then
        sleep 0.12
      fi
    done
    sleep "${SEQUENCE_DELAY_SECONDS}"
  done
  echo "[mc-autostart] World-start click sequence sent."
else
  echo "[mc-autostart] Launcher Play step completed."
fi

exit 0
