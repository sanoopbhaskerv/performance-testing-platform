#!/usr/bin/env bash
#
# Checks the four handoff documents agree with each other before a commit.
#
# They are updated by hand at the end of every increment, which works right up until one of
# them is missed — and the one that gets missed is invisible, because each document reads
# correctly on its own. A fresh context reads all four and believes whichever it opens first.
#
# This checks what can be checked mechanically. It cannot tell you the prose is true; it can
# tell you the four disagree, which is always a bug in at least one of them.
#
#   ./scripts/check-docs.sh
#
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2

DOCS=(HANDOFF.md PLAN.md NEXT-SESSION.md ROADMAP.md)
problems=0

say()  { printf '  %s\n' "$1"; }
fail() { printf '  ✗ %s\n' "$1"; problems=$((problems + 1)); }
ok()   { printf '  ✓ %s\n' "$1"; }

# ── 1. All four exist ────────────────────────────────────────────────────────
echo "The four documents"
for doc in "${DOCS[@]}"; do
  [ -f "$doc" ] || fail "$doc is missing"
done
[ "$problems" -eq 0 ] && ok "all present"

# ── 2. Test counts agree ─────────────────────────────────────────────────────
#
# Every document quotes them, and a stale count is the cheapest possible signal that a
# document was not touched. Phrasing varies ("934 tests", "934 Java tests"); the numbers
# must not.
echo
echo "Test counts"
java_counts=$(grep -ohE '\b[0-9]{3,4} (Java )?tests\b' "${DOCS[@]}" 2>/dev/null \
  | grep -oE '^[0-9]+' | sort -u)
ui_counts=$(grep -ohE '\b[0-9]{2,4} UI tests\b' "${DOCS[@]}" 2>/dev/null \
  | grep -oE '^[0-9]+' | sort -u)

check_one() {
  local label="$1" values="$2"
  if [ -z "$values" ]; then
    say "no $label count stated (fine if none applies)"
  elif [ "$(echo "$values" | wc -l | tr -d ' ')" -gt 1 ]; then
    fail "$label counts disagree: $(echo "$values" | tr '\n' ' ')"
  else
    ok "$label: $values"
  fi
}
# A historical count inside a §3x account ("88 UI tests at the close of S1") is legitimate,
# so a disagreement here is a prompt to look, not proof of a bug. The exit code still trips,
# because looking is the point.
check_one "Java test" "$java_counts"
check_one "UI test" "$ui_counts"

# ── 3. No increment is both done and not done ────────────────────────────────
#
# HANDOFF's status table and PLAN's sequence table both carry per-increment state. They are
# maintained separately and are the two most likely to drift apart.
echo
echo "Increment status"
for inc in $(grep -ohE '\*\*S[0-9]+\*\*|\| \*\*S[0-9]+ ' "${DOCS[@]}" 2>/dev/null \
    | grep -oE 'S[0-9]+' | sort -u -V); do
  handoff=$(grep -E "^\| \*\*$inc( |—|\*\*)" HANDOFF.md 2>/dev/null | head -1)
  plan=$(grep -E "^\| \*\*$inc\*\*" PLAN.md 2>/dev/null | head -1)
  [ -n "$handoff" ] && [ -n "$plan" ] || continue

  h_done=$(echo "$handoff" | grep -ciE 'complete' || true)
  p_done=$(echo "$plan" | grep -ciE 'done' || true)
  if [ "$h_done" != "$p_done" ]; then
    fail "$inc: HANDOFF says $( [ "$h_done" != 0 ] && echo complete || echo 'not complete'), PLAN says $( [ "$p_done" != 0 ] && echo done || echo 'not done')"
  fi
done
[ "$problems" -eq 0 ] && ok "HANDOFF and PLAN agree on every increment"

# ── 4. If code changed, the documents should have too ────────────────────────
#
# Only a prompt. Plenty of commits legitimately touch one and not the other — a doc-only fix,
# or a change split across two commits — so this never fails the check.
echo
echo "Working tree"
code_changed=$(git status --porcelain -- '*.java' '*.ts' '*.tsx' '*.css' '*.sql' '*.yaml' 2>/dev/null | wc -l | tr -d ' ')
docs_changed=$(git status --porcelain -- "${DOCS[@]}" 2>/dev/null | wc -l | tr -d ' ')
if [ "$code_changed" -gt 0 ] && [ "$docs_changed" -eq 0 ]; then
  say "⚠ $code_changed code file(s) changed and no document did — intended?"
else
  ok "$code_changed code file(s), $docs_changed document(s) changed"
fi

echo
if [ "$problems" -gt 0 ]; then
  echo "$problems disagreement(s). Fix them, or explain why the difference is deliberate."
  exit 1
fi
echo "The four documents agree."
