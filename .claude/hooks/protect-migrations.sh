#!/bin/bash
# PreToolUse (Edit|Write|MultiEdit). Flyway checksums applied migrations, so they must never change, and a new
# migration must be numbered above every existing one (plan.md, D4). Everything up to PAYBRIDGE_FROZEN_MIGRATIONS is
# frozen (default 13: V1 to V13 are applied). Raise the default after each deploy. New migrations are also checked for
# money-unsafe SQL (plan-002-004.md, section 8).
. "$(dirname "$0")/lib.sh" || { echo "BLOCKED by PayBridge guardrail: cannot load lib.sh" >&2; exit 2; }
case "$TOOL" in Edit|Write|MultiEdit) ;; *) exit 0 ;; esac

FROZEN=${PAYBRIDGE_FROZEN_MIGRATIONS:-13}
re='/db/migration/V([0-9]+)__[^/]*\.sql$'
[[ "$FILE" =~ $re ]] || exit 0
N=$((10#${BASH_REMATCH[1]}))

if [ "$N" -le "$FROZEN" ]; then
  block "V$N is an applied migration (V1 to V$FROZEN are frozen). Add a new migration instead of editing it."
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

shopt -s nocasematch
if [[ "$TEXT" =~ (truncate|drop[[:space:]]+table|delete[[:space:]]+from) ]]; then
  block "a new migration must not TRUNCATE, DROP TABLE or DELETE FROM. Only the one-time reset (V8) did that. Change shape, never wipe data."
fi
if [[ "$TEXT" =~ (update|delete[[:space:]]+from)[[:space:]]+(only[[:space:]]+)?(public\.)?(ledger_entries|audit_log) ]]; then
  block "ledger_entries and audit_log are append-only. A migration must not update or delete their rows."
fi
if [[ "$TEXT" =~ (double[[:space:]]+precision|[[:space:]]real[[:space:],\)]|[[:space:]]float[0-9]*[[:space:],\)]) ]]; then
  block "money and rates are never floating point. Use BIGINT for minor units and NUMERIC(24,12) for rates."
fi
if [[ "$TEXT" =~ _minor[[:space:]]+(integer|int|int4|smallint|numeric|decimal) ]]; then
  block "a *_minor column must be BIGINT."
fi
exit 0