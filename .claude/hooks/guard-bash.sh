#!/bin/bash
# PreToolUse (Bash). Tests are never skipped, nothing goes straight to main, destructive database commands only touch
# the test database, and secrets never reach a commit.
. "$(dirname "$0")/lib.sh" || { echo "BLOCKED by PayBridge guardrail: cannot load lib.sh" >&2; exit 2; }
[ "$TOOL" = "Bash" ] || exit 0
shopt -s nocasematch

skip='-Dmaven\.test\.skip|-DskipTests|-DskipITs'
if [[ "$CMD" =~ $skip ]]; then
  block "tests must not be skipped. Fix the failing test's cause instead."
fi

push='git[[:space:]]+push'
if [[ "$CMD" =~ $push ]]; then
  force='--force|--force-with-lease|[[:space:]]-f([[:space:]]|$)'
  [[ "$CMD" =~ $force ]] && block "no force pushes."
  tomain='[[:space:]](main|master)([[:space:]]|$|:)'
  [[ "$CMD" =~ $tomain ]] && block "nothing is pushed straight to main. Push a branch and open a pull request."
  branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)
  if [ "$branch" = "main" ] || [ "$branch" = "master" ]; then
    block "you are on $branch. Work on a step branch and open a pull request."
  fi
fi

destroy='dropdb|drop[[:space:]]+(database|table|schema)|truncate[[:space:]]'
if [[ "$CMD" =~ $destroy ]] && [[ ! "$CMD" =~ paybridge_test ]]; then
  block "destructive database commands are only allowed against paybridge_test."
fi
history='(delete[[:space:]]+from|update|truncate)[[:space:]]+(table[[:space:]]+)?(ledger_entries|audit_log)'
if [[ "$CMD" =~ $history ]]; then
  block "ledger_entries and audit_log are append-only. Correct mistakes with a new journal."
fi

commit='git[[:space:]]+commit'
if [[ "$CMD" =~ $commit ]]; then
  [[ "$CMD" =~ --no-verify ]] && block "do not bypass commit checks."
  names=$( { git diff HEAD --name-only; git diff --cached --name-only; git ls-files --others --exclude-standard; } 2>/dev/null )
  if printf '%s\n' "$names" | grep -E '(^|/)\.env(\.[^/]*)?$' | grep -vqE '\.example$'; then
    block "a real .env file is about to be committed."
  fi
  scan=$( { git diff HEAD; git diff --cached; git ls-files --others --exclude-standard -z | xargs -0 cat; } 2>/dev/null )
  if printf '%s' "$scan" | grep -qE 'sk_(live|test)_[A-Za-z0-9]{20,}|JWT_SECRET=[A-Za-z0-9+/]{16,}'; then
    block "a secret (Paystack key or JWT secret) is in the changes about to be committed."
  fi
fi
exit 0
