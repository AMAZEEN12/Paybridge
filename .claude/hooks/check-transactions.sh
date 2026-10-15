#!/bin/bash
# PostToolUse (Edit|Write|MultiEdit). No database transaction is open during an HTTP call (spec 8). This is a rough
# check, not a proof: a client or gateway class is never transactional, and a class that is transactional as a whole
# must not use a gateway, a statement provider or an HTTP client. Method-level cases are left to review and to the
# reserve / call / settle shape of the payout service.
. "$(dirname "$0")/lib.sh" || { echo "BLOCKED by PayBridge guardrail: cannot load lib.sh" >&2; exit 2; }
case "$TOOL" in Edit|Write|MultiEdit) ;; *) exit 0 ;; esac
[ -f "$FILE" ] || exit 0
[[ "$FILE" == */src/main/java/com/academy/paybridge/* ]] || exit 0

if [[ "$FILE" == */client/* || "$FILE" == */gateway/* ]] && grep -q '@Transactional' "$FILE"; then
  block "classes in client/ and gateway/ make HTTP calls and must not be @Transactional. Open the transaction in the service that reserves or settles, never around the call."
fi

http='PayoutGateway|StatementProvider|TransferGateway|RateProvider|RestClient|RestTemplate|WebClient|HttpClient'
if grep -qE '^@Transactional' "$FILE" && grep -qE "\b($http)\b" "$FILE"; then
  block "this class is @Transactional as a whole and also uses a gateway or HTTP client. Put @Transactional on the short reserve and settle methods only, so no transaction is open during the call."
fi
exit 0