# Intent 001: Multi-currency wallets and conversion

Status: draft (updated after the spec brainstorm)      Date: 2026-10-12      Updated: 2026-10-13      Author: Nwafor Blessing
Detailed requirements and design: `spec.md`

## The problem (in your own words)
PayBridge only understands Naira. A customer cannot hold USD, EUR, GBP or any other currency, and cannot move money
between currencies. Without this, international transfers (Intent 002) cannot be built. Customers who live outside
Nigeria are not served at all.

## The outcome we want
- Every customer has **one account** that holds up to **4 active wallets**, each in a different currency.
- The **home wallet** is in the currency of the customer's country and is fixed at sign-up. The other slots start as
  **USD, EUR and GBP**. If the home currency is already USD, EUR or GBP, the customer starts with 3 wallets and one
  free slot to fill with a currency of their choice.
- A customer can **swap** a non-home slot to another supported currency (about 50 currencies). The old wallet is
  **parked**: it keeps its balance and can still receive money, but cannot send or convert until it is swapped back.
- A customer can convert money between their own wallets at a quoted exchange rate. Conversions are **free for each
  customer's first 30 days**, then carry a **small percentage fee**.
- Balances are correct per currency, and the ledger still balances for every conversion.

A non-programmer can check this by registering, seeing the right wallets, converting 10,000 of the home currency into
USD, and seeing both balances change by the quoted amounts.

## Who and what it touches
- **customer**: a stored country, detected from the user's IP at sign-up and fixed afterwards.
- **account**: becomes the container for the customer's wallets (one account per customer).
- **wallet** (new): a balance in one currency inside an account, either active or parked.
- **ledger**: double-entry lines in more than one currency; reconciliation must work per currency.
- **shared/Money**: today it handles Naira only; it must know each currency's decimal places.
- **fx module** (the existing empty `fx` package): gets exchange rates, stores rate snapshots, and issues quotes.
- **Dev funding**: a test funding endpoint per currency.
- **Web page**: show wallets, swap a wallet, and a convert screen.
- **External services**: Open Exchange Rates (free plan) for rates; a geo-IP service for country detection (not chosen
  yet).
- **Database**: new Flyway migrations (`V8` onward), including a one-time reset of test data.

## Decisions made while brainstorming
- **Wallet model**: one account per customer, up to 4 active wallets including the home wallet. One wallet per currency
  per customer. Swapping parks the old wallet instead of deleting it. Parked wallets can receive money but not send or
  convert.
- **Currencies**: about 50 supported currencies in a table (with their decimal places). Sanctioned currencies are on a
  separate exclusion list and can never be used.
- **Country**: detected automatically from IP at sign-up and **fixed afterwards**. Sign-up is **blocked** if the
  country's currency is not supported. Changing the home country is a manual support process (for example a stay of 6
  months or more, with supporting documents), not an in-app feature.
- **Rates**: from Open Exchange Rates, refreshed hourly into a stored snapshot. There is **no fallback rates table**; if
  the latest snapshot is too old, conversions pause. Each quote records the provider and snapshot it used.
- **Quotes**: a conversion quote lasts **60 seconds** and its rate stays locked until the conversion completes or
  fails. A transfer quote (for Intent 002) will last **30 minutes** with the same lock. A quote can be used only once.
- **Fees**: conversions are free for each customer's first 30 days from sign-up, then a small percentage of the amount
  converted (placeholder 0.5%). The fee is shown in the quote.
- **Limits**: conversion limits are set once in USD-equivalent, plus a velocity rule on conversions.
- **Rounding**: the amount the customer receives is rounded down and the fee is rounded up. No carry-over of
  remainders for now.
- **Funding**: foreign wallets are funded by conversion from another wallet and by a dev/test funding endpoint per
  currency (like the existing `DevFundingController`).
- **Existing data**: all test data is wiped by a one-time migration so every customer starts with the new model.

## Constraints
- Money is stored as whole minor units (`long`), never `double`. Decimals (`BigDecimal`) appear only at the API edge.
- Every balance change writes balanced ledger lines in the same transaction. The ledger must balance **per currency**.
- Lock accounts and wallets in sorted order before changing balances. Do not hold a DB transaction open during the
  rates API call.
- Flyway owns the schema: never edit `V1` to `V7`; changes are new migrations. `ddl-auto` stays `validate`.
- `audit_log` and `ledger_entries` stay append-only. The one-time test-data reset is the only exception, allowed
  because there is no real money.
- Modules talk only through their `api/` packages.
- The rates API is on a free tier (1,000 requests a month on Open Exchange Rates): cache rates in the database, never
  spend API calls on restarts, back off after failures, and stop refreshing near the monthly limit.
- Do not break existing NGN internal transfers, Paystack payouts, or the current tests (tests are updated for the new
  schema).
- Free-tier hosting (Render) and no real money: everything stays in test mode.

## Open questions
All eight original questions are answered in `spec.md` (section 3). What is still open:
1. Which geo-IP service do we use for country detection, and what are its limits?
2. Who verifies the sanctions exclusion list, and against which sources, before release?
3. The placeholder values: fee rate, conversion limits, maximum rate age and the cap on active quotes.
4. Local Naira transfers and Paystack payouts need an active NGN wallet. Is it acceptable that customers without one
   do not see these features?

## Out of scope
- Cross-border transfers and their fees (**Intent 002**). The principle is already agreed: charges depend on the
  currencies and locations involved (for example Nigeria to abroad and back) and must be competitive. The fee table
  needs research first. Only the 30-minute transfer quote type is reserved by this intent.
- Any payout to a bank abroad.
- An in-app way to change the home country.
- Carrying rounding remainders to later conversions.
- Admin dashboard, customer dashboard, email/SMS notifications, NUBAN, rate-limiting for HTTP requests, monitoring and
  observability, accessibility, biometrics, animated UI. These become later intents.
