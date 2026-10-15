#!/bin/bash
# PostToolUse (Edit|Write|MultiEdit). Money and rates are long minor units or BigDecimal, never double or float.
# Covers every module that holds or moves money (compliance is left out: its fraud ratios are not money).
. "$(dirname "$0")/lib.sh" || { echo "BLOCKED by PayBridge guardrail: cannot load lib.sh" >&2; exit 2; }
case "$TOOL" in Edit|Write|MultiEdit) ;; *) exit 0 ;; esac
[ -f "$FILE" ] || exit 0

re='/src/main/java/com/academy/paybridge/(account|ledger|fx|transfer|remittance|settlement|shared/money)/'
[[ "$FILE" =~ $re ]] || exit 0

bad=$(grep -vE '^[[:space:]]*(//|\*|/\*)' "$FILE" \
  | sed -E 's#//.*$##' \
  | grep -nE '\b(double|float|Double|Float)\b[[:space:]]+[A-Za-z_]|\((double|float)\)|<(Double|Float)>')
if [ -n "$bad" ]; then
  echo "BLOCKED by PayBridge guardrail: money and rates must be long minor units or BigDecimal, never double or float:" >&2
  echo "$bad" >&2
  exit 2
fi
exit 0