#!/bin/bash
# PostToolUse (Edit|Write|MultiEdit). Never log secrets, webhook secrets, PINs, full account numbers or bank details
# (spec 8). Rough check on log lines: text inside quotes is ignored, and so is a bare null check (logging
# "signature != null" is a yes or no, not the signature), so only values passed to the logger count.
. "$(dirname "$0")/lib.sh" || { echo "BLOCKED by PayBridge guardrail: cannot load lib.sh" >&2; exit 2; }
case "$TOOL" in Edit|Write|MultiEdit) ;; *) exit 0 ;; esac
[ -f "$FILE" ] || exit 0
[[ "$FILE" == */src/main/java/* ]] || exit 0

lines=$(grep -nE '\blog(ger)?\.(trace|debug|info|warn|error)\(' "$FILE" | sed -E 's/"([^"\\]|\\.)*"/""/g' | sed -E 's/[A-Za-z_.()]+[[:space:]]*(!=|==)[[:space:]]*null/NULLCHECK/g')
[ -n "$lines" ] || exit 0

bad=$(printf '%s\n' "$lines" | grep -iE 'secret|apikey|api_key|authorization|password|verif.?hash|signature|bankdetails|\biban\b|\bpin\b')
if [ -n "$bad" ]; then
  echo "BLOCKED by PayBridge guardrail: a log line passes a secret, PIN, signature or bank detail:" >&2
  echo "$bad" >&2
  exit 2
fi
bad=$(printf '%s\n' "$lines" | grep -iE 'accountnumber|account_number|accountno|destinationaccount' | grep -viE 'mask')
if [ -n "$bad" ]; then
  echo "BLOCKED by PayBridge guardrail: a log line passes a full account number. Mask it (Money.maskAccount):" >&2
  echo "$bad" >&2
  exit 2
fi
exit 0