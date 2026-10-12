# Spec 001: Multi-currency wallets and conversion

Status: draft      Date: 2026-10-12      Author: Nwafor Blessing
Implements: Intent 001 (`001-multi-currency-wallets.md`)

This spec turns the intent into requirements and a design that a plan can be written from. Section 2 lists the places
where decisions made while brainstorming this spec differ from the intent; update the intent to match before planning.

---

## 1. Summary

Today PayBridge understands only Naira. After this work:

- Every customer has **one account** that holds up to **4 active wallets**, each in a different currency.
- A customer's **home wallet** (the currency of their country, fixed at sign-up) is always active. The other slots start
  as **USD, EUR and GBP** and can be **swapped** for other supported currencies. A swapped-out wallet is **parked**: it
  keeps its balance and can still receive money, but cannot send or convert until it is swapped back in.
- A customer can **convert** between their own wallets using a quoted rate. Conversions are **free for each customer's
  first 30 days**, then carry a small percentage fee.
- The ledger balances **per currency** for every conversion.

A non-programmer can verify the outcome: register, see the right wallets, fund a wallet with test money, convert 10,000
of the home currency into USD, and see both balances change by exactly the quoted amounts.

---

## 2. Changes from the intent (update intent.md)

| # | Intent says | This spec | Why |
|---|-------------|-----------|-----|
| C1 | Open question 4: is a wallet an account with a currency, or something new? | A **wallet is new**: one account holds up to 4 active wallets. The "up to 5 accounts per customer" rule is replaced by "one account per customer". | Decided while brainstorming. |
| C2 | Home + USD + EUR + GBP are a fixed set. | The non-home slots can be **swapped**; the old wallet is **parked** (receive-only), never deleted. | Decided while brainstorming. |
| C3 | "Live FX API with a rates-table fallback". | **No fallback table.** The last good rate snapshot is stored in the database; if it is too old, conversions pause. | Decided while brainstorming. |
| C4 | Conversions are free; "Fees or spread on conversions" is out of scope. | **Fees are in scope**: free for each customer's first 30 days from sign-up, then a percentage fee. | Decided while brainstorming. |
| C5 | Quote lock about 30 to 60 seconds. | Conversion quotes: **60 seconds**. A separate **transfer quote** type (30 minutes) is reserved for Intent 002. | Decided while brainstorming. |
| C6 | Open question 5: what happens to existing Naira accounts? | **Existing test data is wiped** by a one-time migration. This is a deliberate exception to "ledger and audit log stay append-only" (see section 11, concern A). | Everything is test data; keeping old accounts breaks reconciliation. |
| C7 | Country is auto-detected; rest unspecified. | Country is detected at sign-up, **fixed afterwards**, and sign-up is **blocked** if the country's currency is unsupported. Changing it is a manual support process. | Decided while brainstorming. |

---

## 3. Answers to the intent's open questions

| # | Question | Answer |
|---|----------|--------|
| 1 | IP detection can be wrong; detect once and lock? Unsupported country? | Detect automatically at sign-up and lock. Unsupported or excluded country: sign-up is blocked with a clear message. Wrong detection is fixed through support (R-CTY-3). |
| 2 | Which free FX API, and its limits? | **Open Exchange Rates, free plan**: hourly updates, USD base only, 1,000 requests a month. Behind a `RateProvider` interface (R-RATE). |
| 3 | Currencies with no or three decimals? | Currency table with minor-unit size; `Money` carries the currency (R-MON). |
| 4 | Wallets vs the 5-account rule? | One account, up to 4 active wallets (R-WAL). |
| 5 | Existing Naira accounts? | Wiped (R-MIG). |
| 6 | Other side of a conversion in the ledger? | Ledger-only FX pool and fee accounts per currency (section 7). |
| 7 | Limits and fraud rules? | Limits in USD-equivalent, plus the existing velocity idea applied to conversions (R-LIM). |
| 8 | How long a quote stays locked? | 60 seconds to confirm; the rate stays locked until the conversion completes or fails (R-QUO). |

---

## 4. Scope

**In scope**
- Wallets, parking and swapping; the supported-currency list; the exclusion list.
- Country detection at sign-up and home-wallet creation.
- Rates module in the existing empty `fx` package: rate provider, snapshot storage, quotes.
- Wallet-to-wallet conversion within one customer's account, with fee and limits.
- Per-currency ledger and reconciliation.
- A dev/test funding endpoint per currency.
- Web page: wallet list, swap, convert screen.
- Flyway migrations `V8` onward.

**Out of scope**
- Cross-border transfers and payouts abroad (**Intent 002**). This spec only reserves the transfer quote type.
- Changing a customer's home country in the app (support process only).
- Carrying rounding remainders to later conversions (possible later feature).
- Email/SMS notifications, admin and customer dashboards, HTTP rate limiting, monitoring, NUBAN, animated UI.
- Real money. Everything stays in test mode.

---

## 5. Glossary

- **Account**: the customer's container, with the existing 10-digit account number. One per customer.
- **Wallet**: a balance in one currency inside an account. Status is `ACTIVE` or `PARKED`.
- **Home wallet**: the wallet in the currency of the customer's country. Fixed, always active.
- **Slot**: one of the 4 places for an active wallet. The home wallet takes one.
- **Parked wallet**: a wallet swapped out of a slot. Keeps its balance; can receive but not send or convert.
- **Snapshot**: one stored fetch of rates from the provider (USD base).
- **Quote**: a stored, single-use price for a conversion, with a locked rate.
- **Minor unit**: the smallest unit of a currency (kobo, cent, yen).

---

## 6. Requirements

Priority: **MUST** is needed for this spec to be done; **SHOULD** is wanted but can slip.

### 6.1 Wallets (R-WAL)

- **R-WAL-1 (MUST)** Each customer has exactly one account. A customer has at most **4 `ACTIVE` wallets**, counting
  the home wallet.
- **R-WAL-2 (MUST)** At sign-up the account gets the home wallet plus USD, EUR and GBP wallets, all `ACTIVE`. If the
  home currency is USD, EUR or GBP, only 3 wallets are created and one slot stays free for the customer to fill.
- **R-WAL-3 (MUST)** A customer has **at most one wallet per currency**, active or parked (unique on account +
  currency).
- **R-WAL-4 (MUST)** Swap: the customer chooses a supported currency to activate and, if all 4 slots are full, which
  non-home active wallet to park. If a wallet in that currency already exists (parked), it is reactivated with its
  balance. Otherwise a new wallet is created with balance 0.
- **R-WAL-5 (MUST)** The home wallet cannot be parked or swapped.
- **R-WAL-6 (MUST)** A parked wallet **can receive** money (conversion into it is not allowed, but funding and, later,
  incoming transfers are) and **cannot** be debited: no conversion out, no sending.
- **R-WAL-7 (MUST)** The 4-wallet cap and swap changes are enforced under a lock on the account row, so two
  simultaneous swaps cannot exceed 4 active wallets.
- **R-WAL-8 (MUST)** A frozen account (existing `FROZEN` status) blocks all debits and swaps, parked or not.
- **R-WAL-9 (SHOULD)** When money arrives in a parked wallet, the customer gets an in-app notice through the existing
  notification module (verify at plan stage that it supports this; no email/SMS).
- **R-WAL-10 (MUST)** Every swap, activation and parking is written to the audit log.

### 6.2 Supported currencies (R-CUR)

- **R-CUR-1 (MUST)** About 50 supported currencies live in a `currencies` table (code, name, symbol, minor-unit size),
  seeded by migration. Section 9 has the starting list.
- **R-CUR-2 (MUST)** A separate **exclusion list** (sanctioned currencies) is kept in config. An excluded currency can
  never be a home currency, a swap target, or a conversion target, even if it appears in the table.
- **R-CUR-3 (MUST)** Adding a currency later is a data/config change, not a code change.
- **R-CUR-4 (MUST)** The starting exclusion list is **verified against current OFAC, UN and EU lists before release**.
  The values in section 9 are a starting point, not legal advice.

### 6.3 Country and home wallet (R-CTY)

- **R-CTY-1 (MUST)** At sign-up the customer's country is detected from the request's IP address through a `GeoLocator`
  interface, and stored on the customer (ISO country code).
- **R-CTY-2 (MUST)** A `countries` table maps each supported country to its currency. If the detected country is not
  in it (its currency is unsupported or excluded), **sign-up is rejected** with a clear message and no customer or
  account is created.
- **R-CTY-3 (MUST)** The country and home currency cannot be changed through the app. An operator can re-home a
  customer after document verification (for example a stay of 6 months or more). The spec defines only the effect:
  the new home currency's wallet becomes the home wallet, the old home wallet becomes an ordinary wallet (parked if no
  slot is free), and the action is audit-logged. No customer-facing flow is built here.
- **R-CTY-4 (MUST)** Tests and local development set the country through a config override. The override **must be
  disabled in the production profile**.
- **R-CTY-5 (MUST)** Behind Render's proxy the client IP is read from the correct forwarded header, not the proxy's
  own address.

### 6.4 Money (R-MON)

- **R-MON-1 (MUST)** Amounts are stored as whole minor units (`long`). `_kobo` columns are renamed to `_minor`.
  Decimals (`BigDecimal`) appear only at the API edge.
- **R-MON-2 (MUST)** `Money` carries a currency and uses the currency table's minor-unit size (2 for NGN and USD, 0
  for JPY, 3 for KWD). Formatting uses the currency's own symbol and decimals, not a hard-coded ₦.
- **R-MON-3 (MUST)** Adding or comparing `Money` of different currencies is an error.
- **R-MON-4 (MUST)** Rates are `BigDecimal`, never `double`. All currency arithmetic goes through one conversion
  method, so no other code does it.
- **R-MON-5 (MUST)** Rounding (v1): the amount the customer **receives is rounded down** to the target currency's
  minor unit; the **fee is rounded up** to the source currency's minor unit. No remainder is carried forward.

### 6.5 Rates (R-RATE)

- **R-RATE-1 (MUST)** A `RateProvider` interface hides the provider. The default implementation is **Open Exchange
  Rates**. Swapping providers is a config change.
- **R-RATE-2 (MUST)** The provider app ID is a secret in an environment variable (`OXR_APP_ID`), never in the repo.
- **R-RATE-3 (MUST)** Rates are fetched **hourly** by a scheduled job into a **snapshot** stored in the database (USD
  base, supported currencies only, with the provider's timestamp and our fetch time). The free plan only supports a USD
  base; every other pair is derived (R-RATE-5).
- **R-RATE-4 (MUST)** The app loads the latest snapshot from the database on startup and calls the provider only if it
  is older than the refresh interval. Free-tier restarts must not cost API calls.
- **R-RATE-5 (MUST)** The rate from currency A to currency B is derived from the snapshot as `R[B] / R[A]`, where
  `R[x]` is units of currency x per 1 USD, using enough precision (at least 12 decimal places stored).
- **R-RATE-6 (MUST)** **Staleness:** if the latest snapshot's provider timestamp is older than `max-snapshot-age`
  (default **3 hours**, configurable), quotes and conversions are refused with a clear "rates temporarily unavailable"
  message. There is no fallback rate table.
- **R-RATE-7 (MUST)** The refresh must never run inside a database transaction. A refresh triggered on demand (the
  free-tier host may have been asleep) is single-flight and no more often than once per 10 minutes.
- **R-RATE-8 (MUST)** The request budget is protected: after a failed refresh, retries back off (up to 1 hour). The
  job checks the provider's free usage endpoint and **stops refreshing at 95% of the monthly quota**, logging a
  warning. Tests and local development use a stub provider and never call the real API.
- **R-RATE-9 (MUST)** Rates are mid-market, indicative reference rates. The UI labels them so ("indicative rate").
- **R-RATE-10 (MUST)** Every quote records the provider name, the snapshot id and the snapshot timestamp.

### 6.6 Quotes (R-QUO)

- **R-QUO-1 (MUST)** One quote mechanism with a **type** and a **time limit per type**, both in config:
  `CONVERSION` (default **60 seconds**) and `TRANSFER` (default **30 minutes**, reserved for Intent 002; this spec
  defines it but does not build transfers).
- **R-QUO-2 (MUST)** A quote is created on the server and stored with: customer, type, source and target currency,
  source amount, fee and fee reason, net amount, target amount, rate, snapshot reference, USD-equivalent amount,
  status, expiry. The client sends only the quote id to confirm; it can never supply a rate.
- **R-QUO-3 (MUST)** Quote states: `ACTIVE` (can be confirmed until expiry), `EXECUTING` (confirmed; the rate is
  locked), `COMPLETED`, `FAILED`, `EXPIRED` (not confirmed in time).
- **R-QUO-4 (MUST)** Confirming moves `ACTIVE` to `EXECUTING` with one conditional update (only if still `ACTIVE` and
  not expired). If it changes no row the confirm is rejected. This makes double-clicks and retries safe: **a quote is
  single-use**.
- **R-QUO-5 (MUST)** Once `EXECUTING`, a quote never expires mid-operation. Its rate and fee stay locked until it
  becomes `COMPLETED` or `FAILED`.
- **R-QUO-6 (MUST)** A `FAILED` quote is spent. The customer must request a new quote at the latest rate.
- **R-QUO-7 (MUST)** A customer can hold at most a configured number of `ACTIVE` quotes at once (default 5), so quotes
  cannot be stockpiled.
- **R-QUO-8 (MUST)** A quote stuck in `EXECUTING` longer than a configured limit is marked `FAILED` by a scheduled
  clean-up job. For conversions this should never happen, because the work is one database transaction.

### 6.7 Conversion (R-CONV)

- **R-CONV-1 (MUST)** A customer can convert between **their own active wallets** in different currencies. Source and
  target must both be `ACTIVE`, in the same account, and not excluded.
- **R-CONV-2 (MUST)** Quote maths, in this order:
  1. `fee = ceil(source_amount x fee_rate)` in the source currency's minor unit, or 0 during the free period.
  2. `net = source_amount - fee`.
  3. `target = floor(net x rate x 10^(target_decimals - source_decimals))` in the target currency's minor unit.
  The quote shows source amount, fee, rate, and exactly what the customer will receive.
- **R-CONV-3 (MUST)** A conversion that would produce a target amount of 0 is rejected.
- **R-CONV-4 (MUST)** Confirming runs in **one database transaction**: lock the account, then the two wallets in
  sorted order (`lockInOrder` style), check balance and status again, write the ledger journal (section 7), update both
  balances, mark the quote `COMPLETED`. Any failure rolls back and marks the quote `FAILED`.
- **R-CONV-5 (MUST)** Each completed conversion is audit-logged and keeps a link to its quote and ledger journal.
- **R-CONV-6 (MUST)** Insufficient balance rejects the conversion at both quote time and confirm time.

### 6.8 Fees (R-FEE)

- **R-FEE-1 (MUST)** The fee rate is configurable (placeholder **0.5%**) and applies to the source amount.
- **R-FEE-2 (MUST)** The fee is **0 during each customer's first 30 days**, counted from that customer's sign-up date
  (`fee.free-days`, default 30). It switches on automatically per customer.
- **R-FEE-3 (MUST)** The quote always records the fee and a reason (`PROMO` when waived, `STANDARD` otherwise), so the
  audit trail explains every conversion.
- **R-FEE-4 (MUST)** The fee is part of the locked quote. It cannot change after the quote is issued, even if the
  free period ends while the quote is `EXECUTING`.

### 6.9 Limits and fraud (R-LIM)

- **R-LIM-1 (MUST)** Limits are defined once in **USD-equivalent**, using the same snapshot: a per-conversion maximum
  (placeholder **$1,000**) and a per-customer daily maximum (placeholder **$5,000**), both configurable.
- **R-LIM-2 (MUST)** The USD-equivalent of each conversion is stored on the quote. The daily total is the sum of that
  day's `COMPLETED` and `EXECUTING` conversions for the customer.
- **R-LIM-3 (MUST)** Limits are checked at quote time and again at confirm time.
- **R-LIM-4 (MUST)** A velocity rule applies to conversions (too many in a short window), implemented through the
  `compliance` module's `api/` package, with thresholds in config. The existing Naira transfer rules are unchanged.

### 6.10 Ledger (R-LED)

- **R-LED-1 (MUST)** `ledger_entries` gains a `currency` column and `amount_kobo` becomes `amount_minor`.
- **R-LED-2 (MUST)** Wallet ledger keys are `<account_number>:<CURRENCY>` (for example `0123456789:USD`).
- **R-LED-3 (MUST)** Every journal must balance **per currency**: for each currency in the journal, debits equal
  credits.
- **R-LED-4 (MUST)** Reconciliation reports, per currency: (a) journals whose debits and credits differ; (b) wallets
  whose balance differs from ledger credits minus debits.
- **R-LED-5 (MUST)** `uq_ledger_line` and the append-only trigger stay as they are.

### 6.11 Dev funding and UI (R-DEV, R-UI)

- **R-DEV-1 (MUST)** The dev/test funding endpoint accepts a currency and credits that wallet from `EXT:FUNDING` in
  that currency. It can fund parked wallets too (receiving is allowed). It stays disabled in production.
- **R-UI-1 (MUST)** The web page shows the customer's wallets with currency, balance, and active/parked status.
- **R-UI-2 (MUST)** The convert screen lets the customer pick source, target and amount, shows the quote (rate, fee,
  exact amount received, a 60-second countdown), and confirms by quote id. An expired quote prompts a new one.
- **R-UI-3 (MUST)** A swap control lets the customer pick a currency and, if needed, which wallet to park.
- **R-UI-4 (MUST)** Rate labels say "indicative rate". Fee, including a waived fee, is shown on every quote.

### 6.12 Migration (R-MIG)

- **R-MIG-1 (MUST)** A one-time migration clears customers, accounts, transfers, ledger entries, audit log and
  notifications, restarts identities, and rebuilds the schema for wallets. System accounts are recreated.
- **R-MIG-2 (MUST)** The migration is safe to run on any environment: an empty database (CI, local) and the existing
  Render database.
- **R-MIG-3 (MUST)** The existing local-transfer, Paystack payout and settlement code is updated to operate on the
  account's **NGN wallet**. Local NGN features are available only to accounts that have an active NGN wallet (known
  limitation for non-Nigerian home currencies; see section 12).
- **R-MIG-4 (MUST)** Existing tests are updated for the new schema and still pass.

---

## 7. Design

### 7.1 Data model (suggested migration split; the plan can adjust)

- **V8** reset and base changes: clear data; rename `*_kobo` to `*_minor`; add `ledger_entries.currency`; add
  `customers.country`.
- **V9** `currencies` and `countries` tables with seed data.
- **V10** `wallets`; move balances out of `accounts`.
- **V11** `fx_rate_snapshots` and `quotes`/`conversions`.

**accounts**: id, account_number (unique), customer_id, status (`ACTIVE`/`FROZEN`), version, created_at. Balance moves
to wallets. System accounts (fee income, VAT, stamp duty) each get one NGN wallet.

**wallets**: id, account_id, currency, balance_minor (`CHECK >= 0`), status (`ACTIVE`/`PARKED`), is_home, version,
created_at. `UNIQUE (account_id, currency)`. The cap of 4 active wallets is enforced in the service under the account
lock, because a plain constraint cannot count rows.

**currencies**: code, name, symbol, minor_units, enabled. **countries**: code, name, currency_code.

**fx_rate_snapshots**: id, provider, base_currency, provider_timestamp, fetched_at, rates (JSON of code to rate).

**quotes**: id (UUID), customer_id, type, source_currency, target_currency, source_amount_minor, fee_minor,
fee_reason, net_minor, target_amount_minor, rate (`NUMERIC(24,12)`), snapshot_id, usd_equivalent_minor, status,
expires_at, created_at, completed_at, failure_reason, journal_key.

### 7.2 Conversion journal

For a conversion of `X` (source currency S) into `Y` (target currency T), with fee `F` (omitted when 0) and net
`N = X - F`. Journal key `CONV:<quote_id>`:

| Line | Account | Direction | Currency | Amount |
|------|---------|-----------|----------|--------|
| 1 | customer wallet `<acct>:S` | DEBIT | S | X |
| 2 | `EXT:FX_POOL:S` | CREDIT | S | N |
| 3 | `EXT:FX_FEE:S` | CREDIT | S | F |
| 4 | `EXT:FX_POOL:T` | DEBIT | T | Y |
| 5 | customer wallet `<acct>:T` | CREDIT | T | Y |

Currency S: debits X, credits N + F = X. Currency T: debit Y, credit Y. Each currency balances on its own. The FX pool
and fee accounts are ledger-only (like `EXT:FUNDING`), not rows in `accounts`, so a pool may go negative when
customers convert more into a currency than the platform has received. The sub-minor-unit remainder from rounding
stays in the pool; no extra line is needed.

### 7.3 Module layout

- New rates and quotes code goes in the existing empty `fx` package: `api`, `service`, `client` (Open Exchange Rates
  client), `domain`, `repository`, `web`. A scheduler lives with it.
- Modules talk only through `api/` packages. `account` exposes wallet operations; `ledger` exposes the multi-currency
  journal method; `compliance` exposes the conversion check; `fx` exposes quoting and conversion.
- The `remittance` package stays empty (Intent 002).

### 7.4 Proposed endpoints (names are settled in the plan)

- `GET /api/v1/currencies`
- `GET /api/v1/wallets`
- `POST /api/v1/wallets/swap` (activate a currency, optionally park one)
- `POST /api/v1/conversions/quotes` (source, target, amount) returns a quote
- `POST /api/v1/conversions` (quote id) confirms
- `POST /api/v1/dev/fund` (currency, amount), dev profile only

### 7.5 Configuration (all in `application.yml`, secrets from environment)

```yaml
paybridge:
  wallets:
    max-active: 4
  fx:
    provider: openexchangerates     # app id from OXR_APP_ID
    refresh-interval: 1h
    max-snapshot-age: 3h
    on-demand-refresh-min-gap: 10m
    quota-stop-percent: 95
  quotes:
    conversion-ttl: 60s
    transfer-ttl: 30m               # reserved for Intent 002
    max-active-per-customer: 5
  fee:
    rate: 0.005                     # placeholder
    free-days: 30
  limits:
    conversion-max-usd: 1000        # placeholder
    daily-max-usd: 5000             # placeholder
  currencies:
    excluded: [IRR, KPW, SYP, CUP]  # to be verified before release
```

---

## 8. Constraints carried from the intent

- Money is whole minor units; no `double`.
- Every balance change writes balanced ledger lines in the same transaction.
- Lock in sorted order before changing balances; **never hold a database transaction open during a provider call**.
- Flyway owns the schema: never edit `V1` to `V7`; `ddl-auto` stays `validate`.
- `audit_log` and `ledger_entries` stay append-only (the one-time reset in R-MIG-1 is the only exception).
- Modules talk only through `api/` packages.
- The rates API is on a free tier (1,000 requests a month): cache, back off, stop at 95%.
- Do not break existing NGN internal transfers, Paystack payouts or tests.
- Free-tier hosting and no real money.

---

## 9. Supported currencies (starting list, to review)

50 currencies; minor-unit size in brackets where it is not 2.

NGN, USD, EUR, GBP, CAD, AUD, NZD, CHF, JPY (0), CNY, HKD, SGD, INR, ZAR, GHS, KES, UGX (0), TZS, RWF (0), XOF (0),
XAF (0), EGP, MAD, ZMW, AED, SAR, QAR, KWD (3), BHD (3), OMR (3), ILS, TRY, BRL, MXN, CLP (0), COP, SEK, NOK, DKK,
PLN, CZK, HUF, RON, KRW (0), THB, MYR, IDR, PHP, VND (0), PKR.

Chosen to cover Nigeria's main partner and diaspora currencies, major reserve currencies, and the regional African
currencies, with five zero-decimal and three three-decimal currencies on purpose so that `Money` is tested against
hard cases.

**Starting exclusion list:** IRR, KPW, SYP, CUP. None of these is in the table above. Other currencies may need adding
after verification (R-CUR-4).

---

## 10. Acceptance criteria

1. **The non-programmer test.** Register (test country Nigeria): the page shows NGN, USD, EUR and GBP wallets. Fund
   NGN with 50,000. Convert 10,000 NGN to USD. The NGN balance drops by exactly the quote's source amount; USD rises by
   exactly the quoted amount; every figure matches the quote shown before confirming.
2. **Three-wallet home.** Register with a USD home: wallets are USD, EUR, GBP, with one free slot.
3. **Cap.** With all 4 slots full, activating a fifth currency without parking one is rejected. After parking EUR and
   activating JPY, EUR is `PARKED` with its balance intact.
4. **Parked behavior.** Funding a parked wallet succeeds; converting out of it, or into it, is rejected.
5. **Swap back.** Reactivating EUR restores its old balance; there is still at most one EUR wallet.
6. **Home is fixed.** Parking or swapping the home wallet is rejected.
7. **Blocked sign-up.** Sign-up from a country whose currency is unsupported or excluded creates no customer.
8. **Ledger.** After any conversion, every journal balances per currency, and reconciliation reports no differences
   for any wallet.
9. **Quote lock.** A quote older than 60 seconds is rejected. A confirmed quote converts at exactly the quoted rate
   even if a new snapshot arrives meanwhile.
10. **Single use.** Confirming the same quote twice (including two simultaneous requests) converts once.
11. **Failed quote.** A failed conversion leaves balances unchanged and the quote cannot be reused.
12. **Rounding.** A conversion whose exact result has a fraction pays out the rounded-down amount; a fee with a
    fraction is rounded up; a zero-decimal target (JPY) has no decimals.
13. **Fee window.** For a customer 29 days after sign-up the fee is 0 with reason `PROMO`; after 30 days it is
    charged at the configured rate and shown in the quote.
14. **Limits.** A conversion above the per-conversion or daily USD-equivalent limit is rejected; the limit is the
    same regardless of currency.
15. **Stale rates.** With a snapshot older than the maximum age and the provider unavailable, quotes are refused with
    a clear message and no wrong rate is used.
16. **Migration.** The reset migration runs cleanly on an empty database and on one with the old data, and the
    existing test suite passes after it.

### Tests to write

- Unit: `Money` for 0, 2 and 3 decimal currencies; rounding and fee maths; cross-rate derivation; wallet-cap rule;
  fee-window boundary at day 30.
- Integration (same style as `PayBridgeFlowTest` with `TestDb`): sign-up with country override; full conversion;
  per-currency reconciliation; parked-wallet rules; limits.
- Concurrency: simultaneous confirms of one quote; simultaneous swaps against the cap; conversions touching the same
  wallets in opposite directions (no deadlock).
- Rates: stub provider for staleness, back-off and the 95% quota stop.

---

## 11. Areas of concern

- **A. One-time reset versus append-only.** The reset deletes ledger and audit rows, which the intent forbids. It is
  acceptable only because all data is test data. The migration must be one-time, documented, and unable to run again
  against data that matters. Once real money exists, no reset is allowed.
- **B. Existing code assumes one Naira balance per account.** `Account`, `AccountService`, `LedgerService`,
  reconciliation, transfers, Paystack payouts, settlement and the dev funding controller all read or write
  `balance_kobo` or account-number ledger keys. Changing to wallets touches all of them. This is the largest risk of
  the work and should be planned as its own step before any new feature.
- **C. Multi-wallet locking.** A conversion locks the account and two wallets. Lock order must be consistent
  everywhere (account first, then wallets sorted by id) to avoid deadlocks.
- **D. Rate-provider quota and sleeping host.** An hourly refresh uses about 720 to 744 of 1,000 monthly calls, so
  there is little room. Restarts, on-demand refreshes and retries must not burn the quota (R-RATE-4, 7, 8).
- **E. Rate risk.** With zero fee in the first 30 days and a 60-second lock, the platform carries the rate risk. In
  test mode this is acceptable. The transfer quote (30 minutes) carries more, so Intent 002 must revisit it.
- **F. Sanctions list.** The exclusion list is not legal advice and must be verified (R-CUR-4).
- **G. Data provenance.** Open Exchange Rates is a blend; rates are indicative. Fine for test mode, not for real money.

---

## 12. Open items for the plan stage

1. **Geo-IP provider is not chosen.** It is another external service with its own free-tier limits. The plan must pick
   one, or use a header supplied by the host, behind the `GeoLocator` interface.
2. **Country-to-currency seed data.** The `countries` table needs a row for every country using a supported currency
   (eurozone, the two CFA franc zones, and others). To be generated at plan time.
3. **Notifications for parked wallets (R-WAL-9).** Confirm the existing notification module can carry an in-app notice.
4. **Local NGN features for non-Nigerian accounts.** Local transfers and Paystack payouts need an active NGN wallet.
   Decide whether non-NGN homes simply do not see these features (current assumption).
5. **Swap frequency.** No limit on how often a customer swaps was discussed. Decide whether one is needed.
6. **Placeholders to confirm:** fee rate (0.5%), conversion limits ($1,000 and $5,000), maximum snapshot age (3 hours),
   active-quote cap (5).
7. **Fee revenue reporting.** `EXT:FX_FEE:<CCY>` accumulates fees. Whether and how to report or sweep them is left for
   a later intent.
