#!/usr/bin/env bash
set -euo pipefail

MC_DIR="${HOME}/Library/Application Support/minecraft"
MC_LOG_DIR="${MC_DIR}/logs"
PIPELINE_LOG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/logs/pipeline"
TS="$(date +%Y-%m-%d_%H-%M-%S)"
OUT_DIR="${PIPELINE_LOG_DIR}/mc_logs_${TS}"

mkdir -p "${OUT_DIR}"

[[ -f "${MC_LOG_DIR}/latest.log" ]] && cp "${MC_LOG_DIR}/latest.log" "${OUT_DIR}/latest.log"
[[ -f "${MC_LOG_DIR}/debug.log" ]] && cp "${MC_LOG_DIR}/debug.log" "${OUT_DIR}/debug.log"
[[ -f "${MC_DIR}/launcher_log.txt" ]] && cp "${MC_DIR}/launcher_log.txt" "${OUT_DIR}/launcher_log.txt"
ls "${MC_DIR}"/hs_err_pid*.log >/dev/null 2>&1 && cp "${MC_DIR}"/hs_err_pid*.log "${OUT_DIR}/" || true
DIAG_DIR="${HOME}/Library/Logs/DiagnosticReports"
if [[ -d "${DIAG_DIR}" ]]; then
  ls -t "${DIAG_DIR}"/*.crash 2>/dev/null | head -n 5 | xargs -I{} cp "{}" "${OUT_DIR}/" 2>/dev/null || true
  ls -t "${DIAG_DIR}"/*.ips 2>/dev/null | head -n 5 | xargs -I{} cp "{}" "${OUT_DIR}/" 2>/dev/null || true
fi

echo "Snapshot saved to ${OUT_DIR}"
