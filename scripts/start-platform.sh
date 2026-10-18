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
# Four processes: the demo service to test against (8081), the control plane (8080), the console
# (5173) and the student app (5174) — a real browser client for the demo service, which is what
# makes the recorder worth trying. Ports are cleared before anything starts, because a `java -jar`
# survives the shell that launched it — the next start then fails with "Port 8080 was already in
# use" while curl answers happily from the *stale* jar, and you verify the wrong build.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LOG_DIR="$REPO_ROOT/.local/logs"
DEMO_PORT=8081
API_PORT=8080
UI_PORT=5173
# The student app: a real browser client for the demo service, and the thing worth recording.
# The demo service is API-only, so recording it means recording a JSON page.
STUDENT_UI_PORT=5174
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
  free_port "$STUDENT_UI_PORT" "the student app"
  free_port "$API_PORT" "the control plane"
  free_port "$DEMO_PORT" "the demo service"
}

if [ "$STOP_ONLY" = true ]; then
  step "Stopping anything left running"
  stop_all
  ok "Nothing is listening on $DEMO_PORT, $API_PORT, $UI_PORT or $STUDENT_UI_PORT."
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

if [ ! -d runner-images/playwright-runner/node_modules ]; then
  step "Installing the pinned browser runtime"
  npm ci --prefix runner-images/playwright-runner
fi
step "Checking the Chromium browser runtime"
(cd runner-images/playwright-runner && npx playwright install chromium)

if [ ! -d demo-student-ui/node_modules ]; then
  step "Installing student app dependencies"
  (cd demo-student-ui && npm ci)
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

# ── The Custom Thread Groups plugin ───────────────────────────────────────
#
# Every plan the platform renders needs it: staged load uses the Ultimate Thread Group so
# concurrency glides rather than steps at stage boundaries, and an arrival-rate model uses the
# Free-Form Arrivals Thread Group, which has no vanilla equivalent at all.
#
# Installed here rather than left as an instruction because the control plane refuses to start
# without it — and it refuses because JMeter does not fail on an element it cannot resolve. It logs,
# skips the thread group, and exits successfully, so a missing plugin would present as a run that
# succeeded having generated no load.
ensure_jmeter_plugin() {
  local home ext
  home="$(dirname "$(dirname "$(readlink -f "$(command -v jmeter)" 2>/dev/null || command -v jmeter)")")"
  # Homebrew's `jmeter` is a wrapper in bin/; the real install is under libexec.
  [ -d "$home/libexec/lib/ext" ] && home="$home/libexec"
  ext="$home/lib/ext"

  if ls "$ext"/jmeter-plugins-casutg*.jar >/dev/null 2>&1; then
    return 0
  fi

  step "Installing the JMeter Custom Thread Groups plugin"
  if [ ! -x "$home/bin/PluginsManagerCMD.sh" ]; then
    # The plugins manager ships with JMeter but its CLI wrapper is generated, and it needs
    # cmdrunner under exactly this name to run at all.
    curl -fsSL "https://repo1.maven.org/maven2/kg/apc/cmdrunner/2.3/cmdrunner-2.3.jar" \
      -o "$home/lib/cmdrunner-2.3.jar" 2>/dev/null || true
    java -cp "$ext"/jmeter-plugins-manager-*.jar \
      org.jmeterplugins.repository.PluginManagerCMDInstaller >/dev/null 2>&1 || true
  fi

  if [ -x "$home/bin/PluginsManagerCMD.sh" ] \
      && "$home/bin/PluginsManagerCMD.sh" install jpgc-casutg >/dev/null 2>&1 \
      && ls "$ext"/jmeter-plugins-casutg*.jar >/dev/null 2>&1; then
    ok "Installed jpgc-casutg into $ext"
  else
    warn "Could not install the plugin automatically. Run:"
    warn "  $home/bin/PluginsManagerCMD.sh install jpgc-casutg"
    warn "Falling back to the fake provider, which generates no load."
    PROVIDER="fake"
  fi
}

if [ "$PROVIDER" = "local-jmeter" ]; then
  ensure_jmeter_plugin
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
wait_for "http://localhost:$DEMO_PORT/actuator/health" "Demo service"

# ── Control plane ─────────────────────────────────────────────────────────
step "Starting the control plane on :$API_PORT (provider: $PROVIDER)"
# The recorder bridge is switched on here and only here. Both flags are refused outright under a
# production profile: the local bridge starts Chrome inside the control-plane process, and the
# loopback allowance is what lets a recording browser reach the demo service on this machine. The
# rest of the address floor — metadata, link-local, wildcard, multicast — still applies.
#
# The Playwright workspace root is this checkout, which is what makes a browser journey runnable
# from the console rather than from a terminal: a specification can name
# workspace://demo-student-ui/tests/student-journey.spec.ts and nothing outside this directory.
# Development only — production browser runs use the pinned runner image against a git-pinned commit,
# because a file on disk can change between two runs of the same immutable definition version.
"$JAVA" -jar "$CONTROL_PLANE_JAR" \
  --platform.config-dir=config/teams \
  --platform.orchestration.execution-provider="$PROVIDER" \
  --platform.orchestration.playwright.workspace-root="$REPO_ROOT" \
  --platform.orchestration.recorder.local-browser.enabled=true \
  --platform.orchestration.recorder.local-browser.allow-loopback-target=true \
  --platform.orchestration.recorder.network-enforcement.enabled=false \
  > "$LOG_DIR/control-plane.log" 2>&1 &
# /actuator/health returns 503 locally because the mail health indicator cannot reach SMTP
# (defect D6), so readiness is checked against a real endpoint instead.
wait_for "http://localhost:$API_PORT/api/v1/config/teams" "Control plane" 90

# ── Console ───────────────────────────────────────────────────────────────
step "Starting the console on :$UI_PORT"
(cd platform-ui && npm run dev -- --port "$UI_PORT" > "$LOG_DIR/console.log" 2>&1) &
wait_for "http://localhost:$UI_PORT" "Console"

# ── Student app ───────────────────────────────────────────────────────────
# Started because the recorder needs something worth recording. It proxies /api to the demo
# service, so clicking through it produces exactly the API calls a journey is made of.
step "Starting the student app on :$STUDENT_UI_PORT"
(cd demo-student-ui && npm run dev -- --port "$STUDENT_UI_PORT" > "$LOG_DIR/student-app.log" 2>&1) &
wait_for "http://localhost:$STUDENT_UI_PORT" "Student app"

# ── Ready ─────────────────────────────────────────────────────────────────
cat <<BANNER

${BOLD}Ready.${RESET}

  Console        ${BOLD}http://localhost:$UI_PORT${RESET}   → the "Performance tests" tab
  Team API key   ${BOLD}$API_KEY${RESET}
  Control plane  http://localhost:$API_PORT      ${DIM}(OpenAPI at /swagger-ui.html)${RESET}
  Demo service   http://localhost:$DEMO_PORT      ${DIM}the thing you are load-testing${RESET}
  Student app    http://localhost:$STUDENT_UI_PORT      ${DIM}a real browser client — record this one${RESET}

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
