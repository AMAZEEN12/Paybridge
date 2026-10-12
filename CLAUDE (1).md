# PayBridge

Spring Boot modular monolith (Java 21, Postgres, Flyway, JWT, Log4j2) for learning: local money transfer, bank payouts
through Paystack (test mode), and now multi-currency wallets. Test mode only, no real money. Run all commands from the
folder that contains `pom.xml`.

## Commands
- Run: `mvn spring-boot:run` (needs `DB_PASSWORD` and `JWT_SECRET`; see `.env.example`; Postgres database `paybridge_v3`).
- Unit tests: `mvn test` (no database needed).
- Integration tests: `PayBridgeFlowTest` needs a Postgres database named `paybridge_test` (`createdb paybridge_test`).
  It **skips itself** if the database is missing, and a skipped test is not a pass.
- Web page `http://localhost:8080/`, Swagger `/swagger-ui.html`, health `/actuator/health`.

## Architecture
- Root package `com.academy.paybridge`. Modules: `shared`, `customer`, `account`, `ledger`, `transfer`,
  `compliance`, `notification`, `settlement`, `fx`, `remittance`. `fx`, `remittance` and `settlement` are empty
  scaffolds (`.gitkeep` only). New rates and quote code goes in `fx`.
- Each module has `api/` (the only part other modules may use), `service/`, `domain/`, `repository/`, `web/`.
- **Module rule:** use another module only through its `api/` package. Never call its repository or service directly.
- Endpoints live under `/api/v1/...`. Errors use `ApiException`, `GlobalExceptionHandler` and `ApiError`.
- Static web page: `src/main/resources/static` (`index.html`, `app.js`, `app.css`).

## Conventions
- **Money** is whole minor units (`long`), never `double` or `float`. `BigDecimal` only at the API edge, and for rates.
- **Schema** belongs to Flyway. Never edit an applied migration (`V1` to `V7`). Every change is a new migration.
  `ddl-auto` stays `validate`.
- **Append-only:** `audit_log` and `ledger_entries` are protected by database triggers. Fix mistakes with a new
  journal, never by editing history. The one-time test-data reset in `spec.md` (R-MIG-1) is the only exception.
- **Every balance change** writes balanced ledger lines in the same transaction. Lock accounts in sorted order
  (`AccountApi.lockInOrder`) before changing balances.
- **No database transaction open during an external HTTP call.** Payouts show the pattern: reserve (commit), call,
  then settle.
- Every transfer needs an `Idempotency-Key` header; the same key and request returns the stored result.
- Logging is Log4j2. `spring-boot-starter-logging` is excluded on purpose; do not add it back.
- Secrets live only in environment variables. Never commit `.env` or a real key.
- External APIs are on free tiers (Paystack test allows about 3 name lookups a day; Open Exchange Rates allows 1,000
  requests a month). Tests and local development must never call the real services; use stubs.

## Current work
We follow the AI-native SDLC: `intent.md`, then `spec.md`, then `plan.md`, then build.
- Intent 001, multi-currency wallets and conversion: `001-multi-currency-wallets.md` and `spec.md`. Read both before
  touching wallets, ledger, `Money` or `fx`.
- Start in plan mode. Do not implement before `plan.md` is approved. If implementation departs from the plan, update
  `plan.md` in the same commit.
- If the code and the spec disagree, say so and ask. Do not quietly pick one. Start with the concerns in spec
  section 11 (existing code assumes one Naira balance per account).
- Headline rules from the spec: one account per customer with at most 4 active wallets; parked wallets can receive but
  not send; the ledger balances per currency; the customer receives the rounded-down amount and the fee is rounded up.

## Guardrails (hooks in `.claude/`)
Hooks run on every tool call. If one blocks you, read its message and follow it; do not work around it.
- Edits to `V1` to `V7` migrations are blocked; a new migration must be numbered above every existing one.
- Writing `.env` files or anything that looks like a real Paystack key is blocked.
- `-DskipTests`, force pushes, pushes to `main`, destructive database commands outside `paybridge_test`, and commits
  containing secrets are blocked.
- After each edit, imports that break the module rule or use `double`/`float` for money (in `account`, `ledger`, `fx`,
  `shared/money`) are rejected.
- Start with `PAYBRIDGE_FREEZE_TESTS=1` when existing tests are the proof (plan step 1): existing test files cannot be
  edited, new ones can. After changing hooks, run `bash .claude/hooks/selftest.sh`.

## Verifying your work
Run `mvn test`, with the `paybridge_test` database present so the integration tests actually run, before reporting
any task done, and paste the output. If a test fails, fix the code, not the test. Never skip or delete a failing test.

## Things Claude gets wrong (add a line each time a mistake repeats)
- `README.md` says the ledger is "not built". It is built (`V7`, `ledger` module); the README table is stale.
- Do not change `V1` to `V7`, even to fix a typo.
- Do not use `double` for money or rates.
- Do not reach into another module's service or repository.
- Do not assume `account.balance_kobo` will stay: the spec moves balances to per-currency wallets.
