# PayBridge

A multi-currency money-transfer backend and web app, built as a Spring Boot modular monolith.
Customers hold wallets in several currencies, convert between them at live exchange rates, send money
to other customers, and pay bank accounts in Nigeria and abroad. Every movement of money is recorded
in a double-entry ledger that is reconciled per currency.

**Live demo:** https://paybridge-sbk4.onrender.com/ (free tier: the first request after 15 minutes idle takes about a minute to wake)

> **Test mode only.** PayBridge runs against the Paystack and Flutterwave **sandboxes**. No real money
> moves. It is a learning project, not a licensed payment service.

## What it does

- **Accounts and security:** register, log in with JWT access and refresh tokens, 4-digit transaction PIN
  (3 wrong tries lock it for 30 minutes), login lockout after repeated failures, "freeze my account".
- **Multi-currency wallets:** one account per customer with up to 4 active wallets (home currency plus USD, EUR, GBP).
  The home currency is detected from the customer's IP country at sign-up. A wallet can be swapped for any of
  about 50 supported currencies; the old one is *parked* (it can still receive money but cannot send).
  Sanctioned currencies (IRR, KPW, SYP, CUP) are blocked.
- **Currency conversion:** locked quotes using Open Exchange Rates (60-second quote lifetime, hourly rate refresh,
  stale-rate protection). Free for a customer's first 30 days, then a 0.5% fee.
- **Transfers between customers:** same-currency or cross-currency. A cross-currency transfer shows a quote
  (rate, fee, exact amount received) locked for 30 minutes and usable once.
- **Bank payouts:**
  - Nigerian banks through **Paystack** (account name check first, then payout).
  - Remittance to Nigeria and payouts abroad through **Flutterwave** (NG, GB, US, DE, GH, KE, ZA), with a purpose
    of transfer, a locked quote, status tracking (`PENDING`, `SUCCESSFUL`, `FAILED`) and a full refund on failure.
- **Receive details per wallet:** each wallet gets simulated account details (account number, sort code or routing number, IBAN, SWIFT)
  so it can be shown as receivable like a fintech virtual account. These are made up for the sandbox.
- **Charges (NGN):** stamp duty, bank fee and VAT on the fee, all configurable.
- **Fraud and compliance rules:** transfer and daily limits, velocity limit, large-first-payment and pass-through flags,
  corridor rules (which countries and currencies may send to which), conversion and payout limits in USD.
- **Ledger and reconciliation:** double-entry journals in every currency, plus a reconciliation job that checks the books balance.
- **Settlement (operator tools):** daily batches, matching against partner statements, positions, sweeps and exceptions.
  Admin-key protected; off unless configured.
- **Notifications:** in-app alerts driven by events.
- **Web app and API docs:** a single-page web UI (overview, wallets, send, convert, send abroad, receive) and Swagger.

## The ideas the code protects

1. **All or nothing.** A transfer is one database transaction. Fees go to system accounts inside it, so the total money in the system never changes.
2. **Never twice.** Every transfer needs an `Idempotency-Key`. The same key and request returns the stored result;
   the same key with a different request is refused (`422`). Payout references are also sent to the provider so a retry cannot pay twice.
3. **Always traceable.** `audit_log` and the ledger are append-only (database triggers refuse edits and deletes).
   Refusals, fraud blocks and gateway failures are logged in their own transaction so they survive a rollback.
4. **Money is never a floating-point number.** All amounts are whole minor units (`long`); `BigDecimal` only for rates and at the API edge.
5. **No database transaction during an external call.** Payouts follow *reserve (commit) → call the provider → settle*.
   A provider "no" refunds; a timeout does **not** (the outcome is unknown), so it stays `PENDING` and is verified later.
   Settlement locks the row, so a webhook, a manual verify and the background job can race and the refund still happens at most once.

## Architecture

A modular monolith. A module may use another module **only through its `api/` package**, never its repositories or services.

| Module | Responsibility |
|---|---|
| `shared` | Money and currency types, country registry, errors, config |
| `customer` | Sign-up (with geo-IP country), login, refresh tokens, PIN |
| `account` | Accounts, wallets, swap and park, receive details |
| `ledger` | Double-entry journals, per-currency reconciliation |
| `fx` | Exchange rates, rate snapshots, conversion quotes |
| `transfer` | Customer transfers, Paystack bank payouts, charges |
| `remittance` | Flutterwave payouts abroad and into Nigeria, verification, refunds |
| `compliance` | Fraud rules, limits, corridors, sanctioned currencies |
| `settlement` | Batches, statement matching, positions, sweeps, exceptions |
| `notification` | In-app notifications from events |

**Stack:** Java 21, Spring Boot 4, Spring Security (JWT), Spring Data JPA, PostgreSQL, Flyway (24 migrations),
Log4j2, springdoc (Swagger), Docker, Render. About 320 Java files and about 380 tests.

## Run it on your laptop

You need Java 21 and PostgreSQL (or Docker for Postgres: `docker compose up -d`).

1. Create the database: `createdb paybridge_v3` (or set `DB_NAME` to your own name).
2. Set environment variables (see `.env.example`). Never commit real values.

   | Variable | Meaning |
   |---|---|
   | `DB_PASSWORD` | Postgres password (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER` default to localhost, 5432, paybridge_v3, postgres) |
   | `JWT_SECRET` | At least 32 characters. Signs the login tokens |
   | `OXR_APP_ID` | Open Exchange Rates app id, for live rates |
   | `PAYSTACK_SECRET_KEY` | Paystack **test** key (only with the `paystack` profile) |
   | `PAYBRIDGE_PAYOUT_PROVIDER` | `fake` (default) or `flutterwave` |
   | `FLUTTERWAVE_SECRET_KEY` | Flutterwave **test** key (only with `flutterwave`) |
   | `FLUTTERWAVE_WEBHOOK_SECRET` | Same value as the secret hash in the Flutterwave dashboard |
   | `PAYBRIDGE_CONVERSION_ENABLED` | `true` to turn on conversions |
   | `PAYBRIDGE_SETTLEMENT_ADMIN_KEY` | Long random value; empty keeps settlement endpoints off |
   | `PAYBRIDGE_DEV_FUNDING_ENABLED` | `false` removes the "Add test money" button on a public deployment |

3. Start it: `./mvnw spring-boot:run` (Flyway creates every table on first start).
4. Open http://localhost:8080/ for the app, or http://localhost:8080/swagger-ui.html for the API. Health check: `/actuator/health`.

For the real Paystack test gateway, set `SPRING_PROFILES_ACTIVE=paystack` and `PAYSTACK_PAYOUT_MODE=live`.
Paystack test mode allows only about 3 live name lookups a day; the app counts them and caches names.

## Try it (about three minutes)

1. Register two customers and sign in. Each sets a PIN.
2. On the first customer, press **Add test money** on a wallet.
3. **Send money** to the second customer's account number. Repeat the same request: nothing is charged twice.
4. **Convert** between two of your wallets and see both balances change by the quoted amounts.
5. **Send abroad:** pick a country, enter bank details and a purpose, review the quote, confirm and watch the status.

### Fake gateway: the account number decides what happens

With the fake gateway on (the default), the destination account number picks the outcome:

| Starts with | What happens | What it proves |
|---|---|---|
| `00` | name check fails | nothing is debited when the account cannot be found |
| `77` | provider rejects at once | immediate refund |
| `88` | times out, but the provider did pay | **no** refund on a timeout; verify later says `SUCCESSFUL` |
| `99` | accepted, then fails | one full refund, including charges |
| anything else | accepted, succeeds when verified | the normal path |

## Tests

- `./mvnw test` runs the unit tests (money, charges, fraud rules, corridors, quote math, settlement matching). They need no database.
- Integration tests need a Postgres database named `paybridge_test1` (`createdb paybridge_test1`, or set `TEST_DB_URL`).
  They **skip themselves** if it is missing, and a skipped test is not a pass.
  They cover idempotent replay, ownership, PIN lockout, 20 parallel transfers with no overdraft and money conserved,
  the same key sent 8 times at once, every payout outcome, signed webhooks, the append-only audit log,
  conversions and quotes, and per-currency reconciliation.
- Tests and local runs never call the real Paystack, Flutterwave or Open Exchange Rates; they use stubs.

## Deploy to Render (free)

1. Push to GitHub (check `git status`: no `.env`, no `target/`, no secrets).
2. In Render: **New → Blueprint**, pick the repository. `render.yaml` creates the web service (built from the `Dockerfile`)
   and a free Postgres, and wires the database settings. Paste your test keys when asked.
3. Add the optional variables in the dashboard (`SPRING_PROFILES_ACTIVE`, `PAYSTACK_PAYOUT_MODE`, and so on, see comments in `render.yaml`).
4. Webhooks: set `https://paybridge-sbk4.onrender.com/api/v1/webhooks/paystack` in Paystack (test mode) and
   `https:///paybridge-sbk4.onrender.com//api/v1/webhooks/flutterwave` in Flutterwave (test mode).

Free-tier limits (they change): the service sleeps after 15 minutes idle, scheduled jobs do not run while it sleeps,
and the free Postgres expires after 30 days. Open the app a couple of minutes before a demo.
If it fails at start with a *schema validation* error, set `JPA_DDL_AUTO=none` and check the logs.

## Project documents

The project follows an intent → spec → plan → build workflow. See `docs/`:

| Intent | Topic |
|---|---|
| 001 | Multi-currency wallets and conversion |
| 002 | Currency-aware transfers between customers |
| 004 | Remittance and payouts abroad (Flutterwave) |

Each has a spec and a plan alongside it.

## Known limits (honest list)

- Sandbox only. Rates, fees, limits and the Nigeria-outbound corridor are placeholders that still need review against
  current CBN rules, IMTO requirements and sanctions lists before any real use.
- Wallet receive details are simulated. Real ones would come from a licensed partner bank.
- Settlement statements come from a fake provider, and the daily settlement run is off by default.
- Not built: email and SMS delivery (alerts are in-app and logged), email verification and password reset,
  real funding (direct debit or card), a payout queue and scheduler lock for running more than one instance,
  and monitoring.
