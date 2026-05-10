#!/usr/bin/env bash
set -euo pipefail

# Java 21 setup
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"

# Build
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."

PIPELINE_LOG_DIR="${PROJECT_ROOT}/logs/pipeline"
mkdir -p "${PIPELINE_LOG_DIR}"
PIPELINE_TS="$(date +%Y-%m-%d_%H-%M-%S)"
PIPELINE_LATEST_LOG="${PIPELINE_LOG_DIR}/latest.log"
PIPELINE_RUN_LOG="${PIPELINE_LOG_DIR}/${PIPELINE_TS}.log"

exec > >(tee "${PIPELINE_LATEST_LOG}" "${PIPELINE_RUN_LOG}") 2>&1

echo "== Pipeline start: ${PIPELINE_TS} =="
echo "Project: ${PROJECT_ROOT}"
echo "Java: ${JAVA_HOME}"

GRADLE_ARGS=(clean build)
if [[ "${NO_JARJAR:-}" == "1" ]]; then
  echo "NO_JARJAR=1 -> building without jarJar"
  GRADLE_ARGS+=("-PnoJarJar=true")
fi
if [[ "${SKIP_REOBF:-1}" == "1" ]]; then
  echo "SKIP_REOBF=1 -> skipping reobf tasks (official-name runtime)"
  GRADLE_ARGS+=("-PskipReobf=true")
fi

"${PROJECT_ROOT}/gradlew" "${GRADLE_ARGS[@]}"

# Hard gate: relevant scheduler regression tests must exist and pass on every pipeline run.
TEST_RESULTS_DIR="${PROJECT_ROOT}/build/test-results/test"
SCHEDULER_TEST_XML="${TEST_RESULTS_DIR}/TEST-com.steve.ai.execution.behavior.BehaviorSchedulerTest.xml"
REGISTRY_TEST_XML="${TEST_RESULTS_DIR}/TEST-com.steve.ai.execution.behavior.BehaviorRegistryTest.xml"

for xml in "${SCHEDULER_TEST_XML}" "${REGISTRY_TEST_XML}"; do
  if [[ ! -f "${xml}" ]]; then
    echo "Required regression result missing: ${xml}"
    echo "Aborting jar installation."
    exit 1
  fi
  if grep -q 'failures="0"' "${xml}" && grep -q 'errors="0"' "${xml}"; then
    :
  else
    echo "Required regression test failed: ${xml}"
    echo "Aborting jar installation."
    exit 1
  fi
done
echo "Verified required behavior scheduler regression tests."

# Install mod
MC_DIR="${HOME}/Library/Application Support/minecraft"
MODS_DIR="${HOME}/Library/Application Support/minecraft/mods"
CONFIG_SRC="${PROJECT_ROOT}/config/steve-common.toml"
CONFIG_DST="${MC_DIR}/config/steve-common.toml"
JAR_PRIMARY="${PROJECT_ROOT}/build/libs/steve-ai-mod-1.0.0.jar"
JAR_ALL="${PROJECT_ROOT}/build/libs/steve-ai-mod-1.0.0-all.jar"
JAR_PATH="${JAR_PRIMARY}"
if [[ ! -f "${JAR_PATH}" ]]; then
  echo "Expected jar not found: ${JAR_PATH}"
  exit 1
fi

has_empty_classes() {
  local jar_file="$1"
  # zipinfo columns vary by timestamp formatting; filename is reliably $NF.
  # Example:
  # -rw-r--r--  2.0 unx        0 b-        2 defN 26-Mar-03 22:26 com/.../Foo.class
  zipinfo -l "${jar_file}" | awk '$NF ~ /\.class$/ && $4 == 0 { found=1 } END { exit(found ? 0 : 1) }'
}

if has_empty_classes "${JAR_PATH}"; then
  echo "Primary jar contains empty .class entries: ${JAR_PATH}"
  if [[ -f "${JAR_ALL}" ]] && ! has_empty_classes "${JAR_ALL}"; then
    echo "Falling back to all-jar: ${JAR_ALL}"
    JAR_PATH="${JAR_ALL}"
  else
    echo "No valid fallback jar found. Aborting install."
    exit 1
  fi
fi

JAR_NAME="$(basename "${JAR_PATH}")"
JAR_SIZE_BYTES=$(stat -f%z "${JAR_PATH}")
echo "Using jar: ${JAR_NAME} (${JAR_SIZE_BYTES} bytes)"
echo "== jar contents (first 50) =="
jar tf "${JAR_PATH}" | head -n 50

rm -f "${MODS_DIR}/steve-ai-mod-"*.jar
cp "${JAR_PATH}" "${MODS_DIR}/"

if [[ -f "${CONFIG_SRC}" ]]; then
  mkdir -p "$(dirname "${CONFIG_DST}")"
  cp "${CONFIG_SRC}" "${CONFIG_DST}"
  echo "Synced config to ${CONFIG_DST}"
fi

# Remove loose deps from old experiments to avoid confusion
rm -f "${MODS_DIR}"/caffeine-*.jar
rm -f "${MODS_DIR}"/resilience4j-*.jar
rm -f "${MODS_DIR}"/commons-codec-*.jar
rm -f "${MODS_DIR}"/slf4j-api-*.jar
rm -f "${MODS_DIR}"/kotlin-stdlib*.jar
rm -f "${MODS_DIR}"/annotations-13.0*.jar
rm -f "${MODS_DIR}"/error_prone_annotations-*.jar
rm -f "${MODS_DIR}"/jspecify-*.jar

echo "Installed ${JAR_NAME} to ${MODS_DIR}"

# Snapshot Minecraft logs for debugging
MC_LOG_DIR="${MC_DIR}/logs"
PIPELINE_MC_LOG_DIR="${PIPELINE_LOG_DIR}/mc_logs_${PIPELINE_TS}"
mkdir -p "${PIPELINE_MC_LOG_DIR}"

if [[ -f "${MC_LOG_DIR}/latest.log" ]]; then
  cp "${MC_LOG_DIR}/latest.log" "${PIPELINE_MC_LOG_DIR}/latest.log"
fi
if [[ -f "${MC_LOG_DIR}/debug.log" ]]; then
  cp "${MC_LOG_DIR}/debug.log" "${PIPELINE_MC_LOG_DIR}/debug.log"
fi
if [[ -f "${MC_DIR}/launcher_log.txt" ]]; then
  cp "${MC_DIR}/launcher_log.txt" "${PIPELINE_MC_LOG_DIR}/launcher_log.txt"
fi
ls "${MC_DIR}"/hs_err_pid*.log >/dev/null 2>&1 && cp "${MC_DIR}"/hs_err_pid*.log "${PIPELINE_MC_LOG_DIR}/" || true

echo "Copied Minecraft logs to ${PIPELINE_MC_LOG_DIR}"
