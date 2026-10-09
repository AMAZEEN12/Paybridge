# PayBridge

A Spring Boot modular monolith for learning:
local money transfer, cross-border settlement, and USD → NGN remittance.

**Built so far (the transfer feature):** customers (register, log in with JWT, transaction PIN), naira accounts,
internal transfers (PayBridge → PayBridge), payouts to Nigerian banks through Paystack (test mode),
charges (fee, VAT, stamp duty), notifications, fraud rules, an append-only audit log, a small web page and Swagger.
Currency: NGN only.

## Run it on your laptop

You need Java 21 and Postgres.

1. Create the database once: `createdb paybridge` (or create it in pgAdmin).
2. Set these environment variables (see `.env.example`). Never commit real values.

   | Variable | Meaning |
   |---|---|
   | `DB_PASSWORD` | your Postgres password (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER` default to localhost, 5432, paybridge, postgres) |
   | `JWT_SECRET` | any text of at least 32 characters. Signs the login tokens |
   | `PAYSTACK_SECRET_KEY` | your Paystack **test** secret key. Only needed with the `paystack` profile |

3. Start it: `mvn spring-boot:run`  (Flyway creates every table on the first start).
4. Open **http://localhost:8080/** for the web page, or **http://localhost:8080/swagger-ui.html** for Swagger.

To use the real Paystack **test** gateway instead of the built-in fake one:
`SPRING_PROFILES_ACTIVE=paystack` (and set `PAYSTACK_SECRET_KEY`).

## Try it (about two minutes)

1. Register two customers and sign in (web page: *Create account*). Each sets a 4-digit PIN.
2. Open an account for each and press **Add test money** on the first one (a development-only button).
3. Send ₦20,000 from the first to the second account number (**Send money**). The sender is debited
   ₦20,050: the amount plus ₦50 stamp duty. Press send again with the same form: nothing is charged twice.
4. Choose **To a bank**, pick UBA, enter a 10-digit number. The account name appears before you send.

### Fake gateway: the account number decides what happens

The fake gateway is on unless the `paystack` profile is active. Use these as the **destination bank account number** on a payout:

| Account number starts with | What happens | What it proves |
|---|---|---|
| `00` | name check fails | nothing is debited when the account cannot be found |
| `77` | the provider rejects the payout at once | refund straight away |
| `88` | the request times out, but the provider did pay | the money is **not** refunded on a timeout; verify later says SUCCESSFUL |
| `99` | accepted, then fails | refund once, in full, including charges |
| anything else | accepted, succeeds when verified | the normal path |

## The three ideas the code protects

1. **All or nothing.** An internal transfer is one database transaction. Charges are credited to system accounts
   inside it, so the total money in the system never changes.
2. **Never twice.** Every transfer needs an `Idempotency-Key` header, unique per source account. The same key and request
   returns the stored transfer. The same key with a different request is refused (`422`). Payouts also send our own
   reference to Paystack, so a retry cannot pay twice.
3. **Always traceable.** `audit_log` is append-only (a database trigger refuses edits and deletes). Refusals, fraud blocks and
   gateway failures are written in their own transaction, so they survive a rollback.

### How a payout works (and why it is not one transaction)

`resolve account → create recipient → reserve (debit and save PENDING, committed) → call Paystack with NO transaction open → settle`

- Paystack says **no** (a 4xx answer): refund, because we know it did not go.
- Paystack **times out or answers 5xx**: do **not** refund. The outcome is unknown. It stays PENDING and is verified later.
- Settlement locks the transfer row and only changes a `PENDING` transfer, so a webhook, the verify button and the 30-second
  job can all race and the refund still happens at most once.

## Charges (confirm the numbers before you present them)

All in `application.yml` under `paybridge.charges`, in kobo. Rules as reported in the news, not legal advice:

- **Stamp duty:** ₦50, paid by the sender, on transfers of ₦10,000 and above.
- **Fee:** payouts to other banks only. The file uses the CBN 2026 circular (free up to ₦5,000, ₦10 up to ₦50,000, ₦50 above).
  The 2020 guide was ₦10 / ₦25 / ₦50. A comment in the file shows how to switch.
- **VAT:** 7.5% of the **fee**, not of the amount sent.

## Fraud protection

Transaction PIN (3 wrong tries lock it for 30 minutes), a "freeze my account" button, and rules in `paybridge.fraud`:
single-transfer limit, daily limit, too many transfers in 10 minutes (all **block**), a large first payment to a new
recipient, and money passing straight through an account (both **flag** and warn the owner).
A block gives a generic message to the caller. The real reasons are in `audit_log` and `fraud_flags`.

## Tests

- `mvn test` runs the unit tests (charges, fraud rules, money, webhook signature). They need nothing else.
- The end-to-end tests (`PayBridgeFlowTest`) need a Postgres database named `paybridge_test`: `createdb paybridge_test`.
  They skip themselves if it is missing. They cover: charges, idempotent replay, key reuse, ownership, PIN lockout, freeze,
  20 parallel transfers (no overdraft, money conserved), the same key sent 8 times at once, every payout outcome,
  the signed webhook, the append-only audit log and notifications.

## Deploy to Render (free)

1. Push this folder to a GitHub repository. Check `git status` first: no `.env`, no `target/`, no secrets.
2. In Render: **New → Blueprint**, pick the repository. `render.yaml` creates a web service (built from the `Dockerfile`)
   and a free Postgres, and wires the database settings. Paste your Paystack **test** key when asked (or leave it empty to use the fake gateway).
3. Wait for the build. Open `https://<your-app>.onrender.com/` and `.../swagger-ui.html`. Health check: `/actuator/health`.
4. Webhook (only with the Paystack key): in the Paystack dashboard (test mode) set the webhook URL to
   `https://<your-app>.onrender.com/api/v1/webhooks/paystack`. No custom domain is needed.

Free-tier limits to know about (check Render's pricing page, they change): the service **sleeps after 15 minutes** without traffic and
takes about a minute to wake, and **the scheduled verify job does not run while it sleeps**. The free Postgres expires after 30 days.
Open the app two minutes before a demo.

If the app fails at start with a *schema validation* error, set `JPA_DDL_AUTO=none` in Render's environment and send the log to your mentor.

Production habits: secrets only in Render's environment settings; `PAYBRIDGE_DEV_FUNDING_ENABLED=false` turns off "Add test money" on a public deployment.

## Module rule
A module may only use another module through its `api/` package.
Never call another module's repository or service directly.

## Modules
| Module       | Responsibility                                      | Status |
|--------------|-----------------------------------------------------|--------|
| shared       | Money value object, global errors, config, auditing | built |
| customer     | Customer onboarding, login, PIN                     | built |
| account      | NGN accounts, balances, freeze                      | built |
| ledger       | Double-entry bookkeeping                            | not built |
| transfer     | Local transfers, bank payouts, charges, gateway     | built |
| fx           | USD→NGN rates, quotes, rate locking                 | not built |
| remittance   | Inbound USD paid out in NGN                         | not built |
| settlement   | Cross-border settlement and reconciliation          | not built |
| compliance   | AML, sanctions screening, limits                    | fraud rules built |
| notification | Email/SMS alerts via events                         | built (in-app, log "email") |

## Not built yet (say this out loud in a demo)
A real ledger of debit and credit entries, real funding (direct debit) in place of the test-money button, refresh tokens and token
revocation, email verification and password reset, a payout queue plus a scheduler lock for more than one app instance, real
email and SMS, and the compliance rules (daily limits per customer tier, sanctions checks).
