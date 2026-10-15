#/bin/bash
# PostToolUse (Edit|Write|MultiEdit). Tests never call a real service (spec 8). Test code may use localhost, a local
# server, or a fake, never the real host of a partner.
. "$(dirname "$0")/lib.sh" || { echo "BLOCKED by PayBridge guardrail: cannot load lib.sh" >&2; exit 2; }
case "$TOOL" in Edit|Write|MultiEdit) ;; *) exit 0 ;; esac
[ -f "$FILE" ] || exit 0
[[ "$FILE" == */src/test/* ]] || exit 0

bad=$(grep -nE 'api\.flutterwave\.com|flutterwave\.cloud|api\.paystack\.co|openexchangerates\.org|freeipapi\.com' "$FILE")
if [ -n "$bad" ]; then
  echo "BLOCKED by PayBridge guardrail: a test names a real partner host. Use localhost, a local test server or a fake gateway:" >&2
  echo "$bad" >&2
  exit 2
fi
exit 0