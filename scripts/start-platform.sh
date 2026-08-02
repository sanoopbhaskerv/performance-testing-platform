#!/usr/bin/env bash
#
# Brings up everything needed to try the platform locally, in one command.
#
#   ./scripts/start-platform.sh            # start, building only what is missing
#   ./scripts/start-platform.sh --build    # rebuild the Java modules first
#   ./scripts/start-platform.sh --fake     # force the non-load-generating provider
#   ./scripts/start-platform.sh --stop     # stop whatever a previous run left behind
#
# Ctrl-C stops everything it started.
#
# Three processes: the demo service to test against (8081), the control plane (8080) and the
# console (5173). Ports are cleared before anything starts, because a background `java -jar`
# survives the shell that launched it — the next start then fails with "Port 8080 was already in
# use" while curl answers happily from the *stale* jar, and you verify the wrong build.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LOG_DIR="$REPO_ROOT/.local/logs"
DEMO_PORT=8081
API_PORT=8080
UI_PORT=5173
API_KEY="local-dev-key-not-a-secret"

BUILD=false
FORCE_FAKE=false
STOP_ONLY=false

for arg in "$@"; do
  case "$arg" in
    --build) BUILD=true ;;
    --fake)  FORCE_FAKE=true ;;
    --stop)  STOP_ONLY=true ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# ── Output ────────────────────────────────────────────────────────────────
if [ -t 1 ]; then
  BOLD=$'\033[1m'; DIM=$'\033[2m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'
  RED=$'\033[31m'; RESET=$'\033[0m'
else
  BOLD=""; DIM=""; GREEN=""; YELLOW=""; RED=""; RESET=""
fi

step()  { printf '%s==>%s %s\n' "$BOLD" "$RESET" "$1"; }
ok()    { printf '    %s✓%s %s\n' "$GREEN" "$RESET" "$1"; }
warn()  { printf '    %s!%s %s\n' "$YELLOW" "$RESET" "$1"; }
fail()  { printf '    %sx%s %s\n' "$RED" "$RESET" "$1" >&2; }

# ── Java ──────────────────────────────────────────────────────────────────
# JAVA_HOME is not set in the shell profile on this machine, and Maven fails immediately without
# it. The project targets Java 17 and builds on 21 with --release 17; do not "fix" that.
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
  fi
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  fail "No JDK found. Install one (JDK 21 is what this project is built with) or set JAVA_HOME."
  exit 1
fi
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"

# ── Stopping ──────────────────────────────────────────────────────────────
free_port() {
  local port="$1" label="$2" pids
  pids="$(lsof -ti:"$port" 2>/dev/null || true)"
  if [ -n "$pids" ]; then
    warn "Port $port was already in use by $label — stopping pid(s) $(echo "$pids" | tr '\n' ' ')"
    # shellcheck disable=SC2086
    kill -9 $pids 2>/dev/null || true
    sleep 1
  fi
}

stop_all() {
  free_port "$UI_PORT" "the console"
  free_port "$API_PORT" "the control plane"
  free_port "$DEMO_PORT" "the demo service"
}

if [ "$STOP_ONLY" = true ]; then
  step "Stopping anything left running"
  stop_all
  ok "Nothing is listening on $DEMO_PORT, $API_PORT or $UI_PORT."
  exit 0
fi

cleanup() {
  echo
  step "Stopping"
  stop_all
  ok "Stopped. Logs are in ${LOG_DIR#"$REPO_ROOT"/}/"
}
trap cleanup EXIT INT TERM

# ── Build ─────────────────────────────────────────────────────────────────
CONTROL_PLANE_JAR="platform-core/target/platform-core-1.0.0.jar"
DEMO_JAR="demo-service/target/demo-service-1.0.0.jar"

if [ "$BUILD" = true ] || [ ! -f "$CONTROL_PLANE_JAR" ]; then
  step "Building the platform (this takes a couple of minutes)"
  mvn -B -q -DskipTests package
  ok "Built $CONTROL_PLANE_JAR"
else
  ok "Using the existing $CONTROL_PLANE_JAR — pass --build to rebuild"
fi

if [ "$BUILD" = true ] || [ ! -f "$DEMO_JAR" ]; then
  step "Building the demo service"
  mvn -B -q -f demo-service/pom.xml -DskipTests package
  ok "Built $DEMO_JAR"
fi

if [ ! -d platform-ui/node_modules ]; then
  step "Installing console dependencies"
  (cd platform-ui && npm ci)
fi

# ── Which provider ────────────────────────────────────────────────────────
# The fake provider generates NO load. Its runs are marked synthetic, every objective comes back
# indeterminate and the run ends in Failed / NO_LOAD_GENERATED. That is correct behaviour for a run
# that measured nothing — never "fix" it by making the fake provider claim otherwise.
if [ "$FORCE_FAKE" = true ]; then
  PROVIDER="fake"
elif command -v jmeter >/dev/null 2>&1; then
  PROVIDER="local-jmeter"
else
  PROVIDER="fake"
fi

mkdir -p "$LOG_DIR"

# Readiness is "the server answered at all", deliberately — note the absence of curl's -f.
# Every useful V2 endpoint requires an API key and answers 403 without one, and unauthenticated
# calls return 403 rather than 401 anyway (defect D7). A probe that insisted on a 2xx would report
# a perfectly healthy control plane as failed, which is exactly what the first version of this
# script did.
wait_for() {
  local url="$1" label="$2" attempts="${3:-60}"
  for _ in $(seq 1 "$attempts"); do
    if curl -sS --max-time 3 -o /dev/null "$url" 2>/dev/null; then
      ok "$label is up"
      return 0
    fi
    sleep 1
  done
  fail "$label did not come up. See ${LOG_DIR#"$REPO_ROOT"/}/"
  return 1
}

step "Clearing ports"
stop_all

# ── Demo service ──────────────────────────────────────────────────────────
step "Starting the demo service on :$DEMO_PORT"
"$JAVA" -jar "$DEMO_JAR" > "$LOG_DIR/demo-service.log" 2>&1 &
wait_for "http://localhost:$DEMO_PORT/api/users" "Demo service"

# ── Control plane ─────────────────────────────────────────────────────────
step "Starting the control plane on :$API_PORT (provider: $PROVIDER)"
"$JAVA" -jar "$CONTROL_PLANE_JAR" \
  --platform.config-dir=config/teams \
  --platform.orchestration.execution-provider="$PROVIDER" \
  > "$LOG_DIR/control-plane.log" 2>&1 &
# /actuator/health returns 503 locally because the mail health indicator cannot reach SMTP
# (defect D6), so readiness is checked against a real endpoint instead.
wait_for "http://localhost:$API_PORT/api/v1/config/teams" "Control plane" 90

# ── Console ───────────────────────────────────────────────────────────────
step "Starting the console on :$UI_PORT"
(cd platform-ui && npm run dev -- --port "$UI_PORT" > "$LOG_DIR/console.log" 2>&1) &
wait_for "http://localhost:$UI_PORT" "Console"

# ── Ready ─────────────────────────────────────────────────────────────────
cat <<BANNER

${BOLD}Ready.${RESET}

  Console        ${BOLD}http://localhost:$UI_PORT${RESET}   → the "Performance tests" tab
  Team API key   ${BOLD}$API_KEY${RESET}
  Control plane  http://localhost:$API_PORT      ${DIM}(OpenAPI at /swagger-ui.html)${RESET}
  Demo service   http://localhost:$DEMO_PORT      ${DIM}the thing you are load-testing${RESET}

${BOLD}Try this first${RESET}
  1. Open the console, paste the API key, choose ${BOLD}New test${RESET}.
  2. The ${BOLD}Demo service${RESET} template is pre-selected and runs unedited → ${BOLD}Save test${RESET} → ${BOLD}Run this${RESET}.
  3. Or choose ${BOLD}Import journeys${RESET} and paste a cURL command, an OpenAPI document,
     a browser recording (HAR) or a Postman collection.

BANNER

if [ "$PROVIDER" = "local-jmeter" ]; then
  cat <<REAL
${GREEN}Real load.${RESET} JMeter is on PATH, so runs generate actual traffic against the demo
  service and the numbers in the report mean something. The demo service breaches its
  response-time objective on purpose, so expect a run to end in ${BOLD}Failed${RESET} with a
  breached objective — that is the platform doing its job.

REAL
else
  cat <<SYNTHETIC
${YELLOW}No load will be generated.${RESET} JMeter is not on PATH, so the ${BOLD}fake${RESET} provider is
  in use. Runs are marked synthetic, every objective is reported indeterminate, and the run
  ends in ${BOLD}Failed / NO_LOAD_GENERATED${RESET}. That is correct: the run measured nothing, so
  nothing can pass. For real load: ${BOLD}brew install jmeter${RESET}, then start this again.

SYNTHETIC
fi

printf '%sLogs%s   %s/{demo-service,control-plane,console}.log\n' "$BOLD" "$RESET" "${LOG_DIR#"$REPO_ROOT"/}"
printf '%sStop%s   Ctrl-C here, or ./scripts/start-platform.sh --stop\n\n' "$BOLD" "$RESET"

# Hold the terminal open so Ctrl-C reaches the trap and stops all three.
while true; do sleep 3600; done
