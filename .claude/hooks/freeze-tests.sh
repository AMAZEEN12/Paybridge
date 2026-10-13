#!/bin/bash
# PreToolUse (Edit|Write|MultiEdit). Optional: start Claude with PAYBRIDGE_FREEZE_TESTS=1 for tasks where the existing
# tests are the proof (plan.md step 1). Existing test files cannot change; new ones can. TestDb holds fixtures only.
. "$(dirname "$0")/lib.sh" || { echo "BLOCKED by PayBridge guardrail: cannot load lib.sh" >&2; exit 2; }
[ "$PAYBRIDGE_FREEZE_TESTS" = "1" ] || exit 0
case "$TOOL" in Edit|Write|MultiEdit) ;; *) exit 0 ;; esac
[[ "$FILE" == */src/test/* ]] || exit 0
[[ "$FILE" == */support/TestDb.java ]] && exit 0
if [ -e "$FILE" ]; then
  block "existing tests are frozen for this task. Fix the code, not the test. New test files are allowed."
fi
exit 0
