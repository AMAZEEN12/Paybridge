#!/bin/bash
# PreToolUse (Edit|Write|MultiEdit). Flyway checksums applied migrations, so V1 to V7 must never change, and a new
# migration must be numbered above every existing one (plan.md, D4).
. "$(dirname "$0")/lib.sh" || { echo "BLOCKED by PayBridge guardrail: cannot load lib.sh" >&2; exit 2; }
case "$TOOL" in Edit|Write|MultiEdit) ;; *) exit 0 ;; esac

re='/db/migration/V([0-9]+)__[^/]*\.sql$'
[[ "$FILE" =~ $re ]] || exit 0
N=$((10#${BASH_REMATCH[1]}))

if [ "$N" -le 7 ]; then
  block "V$N is an applied migration (V1 to V7 are frozen). Add a new migration instead of editing it."
fi

if [ ! -e "$FILE" ]; then
  MAX=0
  for f in "$(dirname "$FILE")"/V*__*.sql; do
    [ -e "$f" ] || continue
    b=$(basename "$f"); v=${b#V}; v=$((10#${v%%__*}))
    [ "$v" -gt "$MAX" ] && MAX=$v
  done
  if [ "$N" -le "$MAX" ]; then
    block "a new migration must be numbered above V$MAX (you wrote V$N). Pick the next free number."
  fi
fi
exit 0
