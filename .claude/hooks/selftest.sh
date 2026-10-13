#!/bin/bash
# Run this once on your machine: bash .claude/hooks/selftest.sh
# It feeds each hook a pretend tool call and checks that it blocks (exit 2) or allows (exit 0) as designed.
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

edit_json()  { printf '{"tool_name":"%s","tool_input":{"file_path":"%s","content":"%s"}}' "$1" "$2" "$3"; }
bash_json()  { printf '{"tool_name":"Bash","tool_input":{"command":"%s"}}' "$1"; }

check() { # name expected_exit hook json [env assignment]
  local name=$1 want=$2 hook=$3 json=$4 envs=$5 got
  if [ -n "$envs" ]; then
    printf '%s' "$json" | env "$envs" bash "$HERE/$hook" >/dev/null 2>&1; got=$?
  else
    printf '%s' "$json" | bash "$HERE/$hook" >/dev/null 2>&1; got=$?
  fi
  if [ "$got" = "$want" ]; then PASS=$((PASS+1)); echo "ok    $name"
  else FAIL=$((FAIL+1)); echo "FAIL  $name (wanted exit $want, got $got)"; fi
}

# --- a fake project --------------------------------------------------------------------------------------------
P="$TMP/paybridge"
M="$P/src/main/resources/db/migration"
J="$P/src/main/java/com/academy/paybridge"
T="$P/src/test/java/com/academy/paybridge"
mkdir -p "$M" "$J/transfer/service" "$J/ledger/service" "$J/fx" "$T/support" "$T/transfer"
for n in 1 2 3 4 5 6 7; do : > "$M/V${n}__x.sql"; done
: > "$T/transfer/PayBridgeFlowTest.java"; : > "$T/support/TestDb.java"

echo "== protect-migrations"
check "edit applied V3 is blocked"            2 protect-migrations.sh "$(edit_json Edit  "$M/V3__x.sql" "x")"
check "edit applied V7 is blocked"            2 protect-migrations.sh "$(edit_json Write "$M/V7__x.sql" "x")"
check "new V8 above V7 is allowed"            0 protect-migrations.sh "$(edit_json Write "$M/V8__wallets.sql" "x")"
check "new V5 below the max is blocked"       2 protect-migrations.sh "$(edit_json Write "$M/V5__late.sql" "x")"
: > "$M/V8__wallets.sql"
check "new second V8 is blocked"              2 protect-migrations.sh "$(edit_json Write "$M/V8__other.sql" "x")"
check "editing unmerged V8 is allowed"        0 protect-migrations.sh "$(edit_json Edit  "$M/V8__wallets.sql" "x")"
check "Windows path to V2 is blocked"         2 protect-migrations.sh "$(edit_json Edit  'C:\\app\\src\\main\\resources\\db\\migration\\V2__x.sql' "x")"
check "unrelated file is allowed"             0 protect-migrations.sh "$(edit_json Edit  "$J/transfer/Foo.java" "x")"

echo "== protect-secrets"
check "writing .env is blocked"               2 protect-secrets.sh "$(edit_json Write "$P/.env" "DB_PASSWORD=x")"
check "writing .env.local is blocked"         2 protect-secrets.sh "$(edit_json Write "$P/.env.local" "x")"
check ".env.example is allowed"               0 protect-secrets.sh "$(edit_json Write "$P/.env.example" "DB_PASSWORD=change-me")"
check "real-looking Paystack key is blocked"  2 protect-secrets.sh "$(edit_json Write "$P/a.txt" "key=sk_test_abcdefghijklmnopqrstuvwx1234")"
check "short test fixture key is allowed"     0 protect-secrets.sh "$(edit_json Write "$P/a.txt" "sk_test_secret")"

echo "== guard-bash"
check "mvn test is allowed"                   0 guard-bash.sh "$(bash_json 'mvn test')"
check "-DskipTests is blocked"                2 guard-bash.sh "$(bash_json 'mvn package -DskipTests')"
check "force push is blocked"                 2 guard-bash.sh "$(bash_json 'git push --force origin step-1')"
check "push to main is blocked"               2 guard-bash.sh "$(bash_json 'git push origin main')"
check "dropdb on the real database blocked"   2 guard-bash.sh "$(bash_json 'dropdb paybridge')"
check "truncate on the real database blocked" 2 guard-bash.sh "$(bash_json 'psql -d paybridge -c \"TRUNCATE accounts\"')"
check "truncate on paybridge_test allowed"    0 guard-bash.sh "$(bash_json 'psql -d paybridge_test -c \"TRUNCATE accounts\"')"
check "delete from ledger_entries blocked"    2 guard-bash.sh "$(bash_json 'psql -d paybridge_test -c \"DELETE FROM ledger_entries\"')"

echo "== guard-bash: commits (needs git)"
R="$TMP/repo"; mkdir -p "$R" && cd "$R" && git init -q . && git config user.email t@t && git config user.name t
echo hello > a.txt && git add a.txt && git commit -q -m init
echo "PAYSTACK_SECRET_KEY=sk_test_abcdefghijklmnopqrstuvwx1234" > leak.txt
check "commit with a secret is blocked"       2 guard-bash.sh "$(bash_json 'git add . && git commit -m wip')"
rm leak.txt; echo "DB_PASSWORD=real" > .env
check "commit with a .env file is blocked"    2 guard-bash.sh "$(bash_json 'git add . && git commit -m wip')"
rm .env; echo "more" >> a.txt
check "clean commit is allowed"               0 guard-bash.sh "$(bash_json 'git add . && git commit -m ok')"
check "commit --no-verify is blocked"         2 guard-bash.sh "$(bash_json 'git commit --no-verify -m x')"
git checkout -q -b main 2>/dev/null
check "push while on main is blocked"         2 guard-bash.sh "$(bash_json 'git push origin HEAD')"
cd "$HERE"

echo "== freeze-tests"
check "off by default: edit test allowed"     0 freeze-tests.sh "$(edit_json Edit  "$T/transfer/PayBridgeFlowTest.java" "x")"
check "frozen: edit existing test blocked"    2 freeze-tests.sh "$(edit_json Edit  "$T/transfer/PayBridgeFlowTest.java" "x")" PAYBRIDGE_FREEZE_TESTS=1
check "frozen: new test file allowed"         0 freeze-tests.sh "$(edit_json Write "$T/transfer/NewTest.java" "x")" PAYBRIDGE_FREEZE_TESTS=1
check "frozen: TestDb fixture allowed"        0 freeze-tests.sh "$(edit_json Edit  "$T/support/TestDb.java" "x")" PAYBRIDGE_FREEZE_TESTS=1
check "frozen: main code allowed"             0 freeze-tests.sh "$(edit_json Edit  "$J/transfer/service/Foo.java" "x")" PAYBRIDGE_FREEZE_TESTS=1

echo "== check-modules"
printf 'package x;\nimport com.academy.paybridge.account.api.AccountApi;\nimport com.academy.paybridge.shared.money.Money;\nimport com.academy.paybridge.transfer.domain.Transfer;\n' > "$J/transfer/service/Ok.java"
check "own module and api imports allowed"    0 check-modules.sh "$(edit_json Edit "$J/transfer/service/Ok.java" "x")"
printf 'package x;\nimport com.academy.paybridge.account.service.AccountService;\n' > "$J/transfer/service/Bad.java"
check "other module's service is blocked"     2 check-modules.sh "$(edit_json Edit "$J/transfer/service/Bad.java" "x")"
printf 'package x;\nimport com.academy.paybridge.ledger.repository.LedgerEntryRepository;\n' > "$J/transfer/service/Bad2.java"
check "other module's repository is blocked"  2 check-modules.sh "$(edit_json Edit "$J/transfer/service/Bad2.java" "x")"

echo "== check-money-types"
printf 'package x;\n// double-entry bookkeeping\n/** a double entry ledger */\nclass A { long minor; java.math.BigDecimal rate; }\n' > "$J/ledger/service/Fine.java"
check "comments and BigDecimal are allowed"   0 check-money-types.sh "$(edit_json Edit "$J/ledger/service/Fine.java" "x")"
printf 'package x;\nclass A { double rate = 1.5; }\n' > "$J/ledger/service/Dbl.java"
check "double field is blocked"               2 check-money-types.sh "$(edit_json Edit "$J/ledger/service/Dbl.java" "x")"
printf 'package x;\nclass A { long f(long x) { return (long) ((double) x * 2); } }\n' > "$J/ledger/service/Cast.java"
check "double cast is blocked"                2 check-money-types.sh "$(edit_json Edit "$J/ledger/service/Cast.java" "x")"
printf 'package x;\nclass A { double rate = 1.5; }\n' > "$J/transfer/service/Other.java"
check "other modules are not checked"         0 check-money-types.sh "$(edit_json Edit "$J/transfer/service/Other.java" "x")"

echo
echo "passed: $PASS   failed: $FAIL"
[ "$FAIL" = "0" ]
