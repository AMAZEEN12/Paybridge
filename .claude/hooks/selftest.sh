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
mkdir -p "$M" "$J/transfer/service" "$J/ledger/service" "$J/fx" "$J/remittance/service" "$J/remittance/client" "$J/settlement/service" "$J/compliance/service" "$T/support" "$T/transfer"
for n in 1 2 3 4 5 6 7; do : > "$M/V${n}__x.sql"; done
: > "$T/transfer/PayBridgeFlowTest.java"; : > "$T/support/TestDb.java"

echo "== protect-migrations"
check "edit applied V3 is blocked"            2 protect-migrations.sh "$(edit_json Edit  "$M/V3__x.sql" "x")" PAYBRIDGE_FROZEN_MIGRATIONS=7
check "edit applied V7 is blocked"            2 protect-migrations.sh "$(edit_json Write "$M/V7__x.sql" "x")" PAYBRIDGE_FROZEN_MIGRATIONS=7
check "new V8 above V7 is allowed"            0 protect-migrations.sh "$(edit_json Write "$M/V8__wallets.sql" "x")" PAYBRIDGE_FROZEN_MIGRATIONS=7
check "new V5 below the max is blocked"       2 protect-migrations.sh "$(edit_json Write "$M/V5__late.sql" "x")" PAYBRIDGE_FROZEN_MIGRATIONS=7
: > "$M/V8__wallets.sql"
check "new second V8 is blocked"              2 protect-migrations.sh "$(edit_json Write "$M/V8__other.sql" "x")" PAYBRIDGE_FROZEN_MIGRATIONS=7
check "editing unmerged V8 is allowed"        0 protect-migrations.sh "$(edit_json Edit  "$M/V8__wallets.sql" "x")" PAYBRIDGE_FROZEN_MIGRATIONS=7
check "Windows path to V2 is blocked"         2 protect-migrations.sh "$(edit_json Edit  'C:\\app\\src\\main\\resources\\db\\migration\\V2__x.sql' "x")" PAYBRIDGE_FROZEN_MIGRATIONS=7
check "unrelated file is allowed"             0 protect-migrations.sh "$(edit_json Edit  "$J/transfer/Foo.java" "x")" PAYBRIDGE_FROZEN_MIGRATIONS=7

check "default freeze covers V12"              2 protect-migrations.sh "$(edit_json Edit  "$M/V12__x.sql" "x")"
check "default freeze covers V13"              2 protect-migrations.sh "$(edit_json Write "$M/V13__x.sql" "x")"
check "new V14 above the freeze is allowed"    0 protect-migrations.sh "$(edit_json Write "$M/V14__transfers_minor.sql" "ALTER TABLE transfers RENAME COLUMN amount_kobo TO amount_minor;")"
check "TRUNCATE in a new migration blocked"    2 protect-migrations.sh "$(edit_json Write "$M/V15__a.sql" "TRUNCATE transfers;")"
check "DROP TABLE in a new migration blocked"  2 protect-migrations.sh "$(edit_json Write "$M/V15__b.sql" "DROP TABLE transfers;")"
check "DELETE FROM in a new migration blocked" 2 protect-migrations.sh "$(edit_json Write "$M/V15__c.sql" "DELETE FROM transfers;")"
check "UPDATE ledger_entries blocked"          2 protect-migrations.sh "$(edit_json Write "$M/V15__d.sql" "UPDATE ledger_entries SET currency = 1;")"
check "float money column blocked"             2 protect-migrations.sh "$(edit_json Write "$M/V15__e.sql" "ALTER TABLE t ADD COLUMN rate DOUBLE PRECISION;")"
check "int _minor column blocked"              2 protect-migrations.sh "$(edit_json Write "$M/V15__f.sql" "ALTER TABLE t ADD COLUMN fee_minor INTEGER;")"
check "BIGINT and NUMERIC(24,12) allowed"      0 protect-migrations.sh "$(edit_json Write "$M/V15__g.sql" "ALTER TABLE t ADD COLUMN fee_minor BIGINT, ADD COLUMN rate NUMERIC(24,12);")"

echo "== protect-secrets"
check "writing .env is blocked"               2 protect-secrets.sh "$(edit_json Write "$P/.env" "DB_PASSWORD=x")"
check "writing .env.local is blocked"         2 protect-secrets.sh "$(edit_json Write "$P/.env.local" "x")"
check ".env.example is allowed"               0 protect-secrets.sh "$(edit_json Write "$P/.env.example" "DB_PASSWORD=change-me")"
check "real-looking Paystack key is blocked"  2 protect-secrets.sh "$(edit_json Write "$P/a.txt" "key=sk_test_abcdefghijklmnopqrstuvwx1234")"
check "short test fixture key is allowed"     0 protect-secrets.sh "$(edit_json Write "$P/a.txt" "sk_test_secret")"

check "Flutterwave test key is blocked"       2 protect-secrets.sh "$(edit_json Write "$P/a.txt" "test-placeholder")"
check "Flutterwave live key is blocked"       2 protect-secrets.sh "$(edit_json Write "$P/a.txt" "live-placeholder")"
check "webhook secret value is blocked"       2 protect-secrets.sh "$(edit_json Write "$P/a.txt" "FLUTTERWAVE_WEBHOOK_SECRET=abcdef0123456789abcdef")"
check "admin key value is blocked"            2 protect-secrets.sh "$(edit_json Write "$P/a.txt" "PAYBRIDGE_SETTLEMENT_ADMIN_KEY=abcdef0123456789abcdef")"
check "placeholder value is allowed"          0 protect-secrets.sh "$(edit_json Write "$P/.env.example" "FLUTTERWAVE_SECRET_KEY=<your-flutterwave-test-key>")"
check "yml reference is allowed"              0 protect-secrets.sh "$(edit_json Write "$P/a.yml" "secret-key: \${FLUTTERWAVE_SECRET_KEY:}")"

echo "== guard-bash"
check "mvn test is allowed"                   0 guard-bash.sh "$(bash_json 'mvn test')"
check "-DskipTests is blocked"                2 guard-bash.sh "$(bash_json 'mvn package -DskipTests')"
check "force push is blocked"                 2 guard-bash.sh "$(bash_json 'git push --force origin step-1')"
check "push to main is blocked"               2 guard-bash.sh "$(bash_json 'git push origin main')"
check "live Flutterwave key in a command"      2 guard-bash.sh "$(bash_json 'export K=FLWSECK-abcdef0123456789abcdef-X')"
check "curl to Flutterwave is blocked"        2 guard-bash.sh "$(bash_json 'curl https://api.flutterwave.com/v3/banks/NG')"
check "curl to Paystack is blocked"           2 guard-bash.sh "$(bash_json 'curl https://api.paystack.co/bank')"
check "curl to localhost is allowed"          0 guard-bash.sh "$(bash_json 'curl http://localhost:8080/actuator/health')"
check "dropdb on the real database blocked"   2 guard-bash.sh "$(bash_json 'dropdb paybridge')"
check "truncate on the real database blocked" 2 guard-bash.sh "$(bash_json 'psql -d paybridge -c \"TRUNCATE accounts\"')"
check "truncate on paybridge_test allowed"    0 guard-bash.sh "$(bash_json 'psql -d paybridge_test -c \"TRUNCATE accounts\"')"
check "delete from ledger_entries blocked"    2 guard-bash.sh "$(bash_json 'psql -d paybridge_test -c \"DELETE FROM ledger_entries\"')"

echo "== guard-bash: commits (needs git)"
R="$TMP/repo"; mkdir -p "$R" && cd "$R" && git init -q . && git config user.email t@t && git config user.name t
echo hello > a.txt && git add a.txt && git commit -q -m init
echo "PAYSTACK_SECRET_KEY=sk_test_abcdefghijklmnopqrstuvwx1234" > leak.txt
check "commit with a secret is blocked"       2 guard-bash.sh "$(bash_json 'git add . && git commit -m wip')"
rm leak.txt
echo "FLUTTERWAVE_SECRET_KEY=abcdef0123456789abcdef" > leak2.txt
check "commit with a Flutterwave secret blocked" 2 guard-bash.sh "$(bash_json 'git add . && git commit -m wip')"
rm leak2.txt; echo "DB_PASSWORD=real" > .env
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
printf 'package x;\nclass A { double rate = 1.5; }\n' > "$J/remittance/service/Dbl.java"
check "remittance double is blocked"          2 check-money-types.sh "$(edit_json Edit "$J/remittance/service/Dbl.java" "x")"
printf 'package x;\nclass A { float fee = 1f; }\n' > "$J/settlement/service/Flt.java"
check "settlement float is blocked"           2 check-money-types.sh "$(edit_json Edit "$J/settlement/service/Flt.java" "x")"
printf 'package x;\nclass A { double passThroughRatio = 1.5; }\n' > "$J/compliance/service/Ratio.java"
check "compliance ratios are not money"       0 check-money-types.sh "$(edit_json Edit "$J/compliance/service/Ratio.java" "x")"

echo "== check-transactions"
mkdir -p "$J/remittance/gateway"
printf 'package x;\n@Transactional\nclass PayoutService { PayoutGateway g; }\n' > "$J/remittance/service/Whole.java"
check "class-level tx using a gateway blocked" 2 check-transactions.sh "$(edit_json Edit "$J/remittance/service/Whole.java" "x")"
printf 'package x;\nclass PayoutService { PayoutGateway g; @Transactional void reserve() {} }\n' > "$J/remittance/service/Methods.java"
check "method-level tx beside a gateway ok"   0 check-transactions.sh "$(edit_json Edit "$J/remittance/service/Methods.java" "x")"
printf 'package x;\n@Transactional\nclass LedgerThing { }\n' > "$J/ledger/service/Plain.java"
check "class-level tx without a gateway ok"   0 check-transactions.sh "$(edit_json Edit "$J/ledger/service/Plain.java" "x")"
printf 'package x;\nclass FlutterwaveClient { @Transactional void call() {} }\n' > "$J/remittance/client/FlutterwaveClient.java"
check "transactional client is blocked"       2 check-transactions.sh "$(edit_json Edit "$J/remittance/client/FlutterwaveClient.java" "x")"

echo "== check-logging"
printf 'package x;\nclass A { void f() { log.info("payout {} sent", reference); } }\n' > "$J/remittance/service/LogOk.java"
check "plain log line is allowed"             0 check-logging.sh "$(edit_json Edit "$J/remittance/service/LogOk.java" "x")"
printf 'package x;\nclass A { void f() { log.warn("bad signature, check FLUTTERWAVE_WEBHOOK_SECRET"); } }\n' > "$J/remittance/service/LogText.java"
check "words inside the message are allowed"  0 check-logging.sh "$(edit_json Edit "$J/remittance/service/LogText.java" "x")"
printf 'package x;\nclass A { void f() { log.info("key {}", props.secretKey()); } }\n' > "$J/remittance/service/LogSecret.java"
check "logging a secret is blocked"           2 check-logging.sh "$(edit_json Edit "$J/remittance/service/LogSecret.java" "x")"
printf 'package x;\nclass A { void f() { log.debug("hook {}", request.header); } }\n' > "$J/remittance/service/LogHeader.java"
check "header word alone is allowed"          0 check-logging.sh "$(edit_json Edit "$J/remittance/service/LogHeader.java" "x")"
printf 'package x;\nclass A { void f() { log.info("signed {}", signature != null); } }\n' > "$J/remittance/service/LogNull.java"
check "a null check on a signature is allowed" 0 check-logging.sh "$(edit_json Edit "$J/remittance/service/LogNull.java" "x")"
printf 'package x;\nclass A { void f() { log.info("to {}", accountNumber); } }\n' > "$J/remittance/service/LogAcct.java"
check "full account number is blocked"        2 check-logging.sh "$(edit_json Edit "$J/remittance/service/LogAcct.java" "x")"
printf 'package x;\nclass A { void f() { log.info("to {}", Money.maskAccount(accountNumber)); } }\n' > "$J/remittance/service/LogMask.java"
check "masked account number is allowed"      0 check-logging.sh "$(edit_json Edit "$J/remittance/service/LogMask.java" "x")"

echo "== check-tests-offline"
printf 'class T { String u = "http://localhost:8080/x"; }\n' > "$T/transfer/LocalTest.java"
check "localhost in a test is allowed"        0 check-tests-offline.sh "$(edit_json Edit "$T/transfer/LocalTest.java" "x")"
printf 'class T { String u = "https://api.flutterwave.com/v3"; }\n' > "$T/transfer/RealTest.java"
check "Flutterwave host in a test blocked"    2 check-tests-offline.sh "$(edit_json Edit "$T/transfer/RealTest.java" "x")"
printf 'class T { String u = "https://openexchangerates.org/api"; }\n' > "$T/transfer/OxrTest.java"
check "Open Exchange Rates host blocked"      2 check-tests-offline.sh "$(edit_json Edit "$T/transfer/OxrTest.java" "x")"
printf 'class M { String u = "https://api.flutterwave.com/v3"; }\n' > "$J/transfer/Main.java"
check "real host in main code is allowed"     0 check-tests-offline.sh "$(edit_json Edit "$J/transfer/Main.java" "x")"

echo
echo "passed: $PASS   failed: $FAIL"
[ "$FAIL" = "0" ]