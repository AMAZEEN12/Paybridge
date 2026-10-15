#!/bin/bash
# PreToolUse (Edit|Write|MultiEdit). Keeps real secrets out of files the agent writes.
. "$(dirname "$0")/lib.sh" || { echo "BLOCKED by PayBridge guardrail: cannot load lib.sh" >&2; exit 2; }
. "$(dirname "$0")/patterns.sh" || { echo "BLOCKED by PayBridge guardrail: cannot load patterns.sh" >&2; exit 2; }
case "$TOOL" in Edit|Write|MultiEdit) ;; *) exit 0 ;; esac

base=$(basename "$FILE")
re='^\.env(\..*)?$'
if [[ "$base" =~ $re ]] && [ "$base" != ".env.example" ]; then
  block "do not write $base. Real values live in your shell or in Render's environment settings; only .env.example may be edited."
fi

if printf '%s' "$TEXT" | grep -qE "$SECRET_RE"; then
  block "this looks like a real key or secret (Paystack, Flutterwave, JWT, webhook or admin key). Use a placeholder such as <your-test-key> and read the real value from the environment."
fi
exit 0