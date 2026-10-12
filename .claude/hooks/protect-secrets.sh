#!/bin/bash
# PreToolUse (Edit|Write|MultiEdit). Keeps real secrets out of files the agent writes.
. "$(dirname "$0")/lib.sh" || { echo "BLOCKED by PayBridge guardrail: cannot load lib.sh" >&2; exit 2; }
case "$TOOL" in Edit|Write|MultiEdit) ;; *) exit 0 ;; esac

base=$(basename "$FILE")
re='^\.env(\..*)?$'
if [[ "$base" =~ $re ]] && [ "$base" != ".env.example" ]; then
  block "do not write $base. Real values live in your shell or in Render's environment settings; only .env.example may be edited."
fi

if printf '%s' "$TEXT" | grep -qE 'sk_(live|test)_[A-Za-z0-9]{20,}'; then
  block "this looks like a real Paystack key. Use a placeholder such as <your-test-key> and read the real one from the environment."
fi
exit 0
