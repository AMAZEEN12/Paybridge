#!/bin/bash
# PostToolUse (Edit|Write|MultiEdit). Module rule: a module may use another module only through its api/ package.
. "$(dirname "$0")/lib.sh" || { echo "BLOCKED by PayBridge guardrail: cannot load lib.sh" >&2; exit 2; }
case "$TOOL" in Edit|Write|MultiEdit) ;; *) exit 0 ;; esac
[ -f "$FILE" ] || exit 0

re='/src/main/java/com/academy/paybridge/([a-z]+)/'
[[ "$FILE" =~ $re ]] || exit 0
mod=${BASH_REMATCH[1]}
[ "$mod" = "shared" ] && exit 0

bad=$(grep -nE '^import (static )?com\.academy\.paybridge\.[a-z]+\.' "$FILE" \
  | grep -vE "paybridge\.(shared|$mod)\." \
  | grep -vE 'paybridge\.[a-z]+\.api\.')
if [ -n "$bad" ]; then
  echo "BLOCKED by PayBridge guardrail: module '$mod' imports another module's internals. Use that module's api/ package only:" >&2
  echo "$bad" >&2
  exit 2
fi
exit 0
