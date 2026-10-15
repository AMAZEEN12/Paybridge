# Intent 002: Currency-aware transfers between PayBridge customers

Status: draft (written after the brainstorm)      Date: 2026-10-15      Author: Nwafor Blessing
Follows: Intent 001 (`001-multi-currency-wallets.md`, `spec.md`, `plan.md`)
Phase: transfer (this intent), settlement (Intent 003), remittance and payout abroad (Intent 004). Intents 003 and 004
are not written yet; they get their own brainstorm.

## The problem (in your own words)
Intent 001 gave every customer up to four wallets and a way to convert between them. A customer still cannot **send**
anything except Naira. Local transfers read the NGN wallet only, the `transfers` table, charge settings and fraud
thresholds are still in kobo, the notifications describe Naira only, and the 30-minute **transfer quote** that
Intent 001 reserved is never used. A customer in the UK with a GBP wallet cannot pay a customer in Nigeria, and a
Nigerian customer cannot pay a friend's USD wallet.

## The outcome we want
- A customer can send money from **any active wallet** to another PayBridge customer, in the same currency or a
  different one.
- The sender finds out which currencies the recipient can receive from the **account lookup**, which returns the
  recipient's name and those currencies. A parked wallet counts as able to receive. The sender picks the currency the
  recipient will receive.
- **Receive currency equals the sender's wallet currency:** the recipient's wallet in that currency is credited with
  exactly what was sent. There is no quote and no PayBridge fee.
- **Receive currency differs:** the sender asks for a **transfer quote** showing the rate, the fee and the exact amount
  the recipient will get. The quote is locked for 30 minutes, can be used once, and is then confirmed.
- If the chosen receive currency is not one the recipient can receive, the transfer is **refused** with a clear
  message.
- Sender and recipient are **notified** when a transfer succeeds, and the sender is notified when a transfer fails after
  it was confirmed.
- The ledger balances per currency for every transfer, and reconciliation stays clean.
- The temporary Naira-only code from Intent 001 goes away: the five `LedgerApi` kobo methods
  (`recordFunding`, `recordInternal`, `recordReserve`, `recordPayoutSuccess`, `recordPayoutRefund`) and the NGN-only
  `Money` helpers are retired where transfers no longer need them.

A non-programmer can check this by registering two customers (the second has NGN, USD, EUR and GBP wallets) and funding
the first with USD. Sending 100 USD to the second, who receives USD, moves both balances by exactly 100 USD and notifies
both customers. Sending 100 USD to the second, who receives NGN, shows a quote (rate, fee, naira received) and a
countdown, and the confirmed transfer matches the quote. Choosing a receive currency the second customer cannot
receive is refused with a clear message.

## Who and what it touches
- **transfer**: `Transfer` and the `transfers` table become `_minor` with a currency (Intent 001's decision D1 kept
  `_kobo` here until this intent). `TransferService`, `TransferSettlementService` and `ChargeCalculator` become
  currency-aware. The Paystack payout path stays Naira only.
- **fx**: the `TRANSFER` quote type is switched on. A transfer quote needs a destination (the recipient's account), so
  the `quotes` table needs a migration. The 30-minute lifetime and single-use rules from Intent 001 apply unchanged.
- **account**: the recipient lookup returns the currencies the account can receive; credit by currency already exists.
- **ledger**: transfers are posted through the multi-currency journal methods; the kobo methods are removed.
- **compliance**: non-Naira transfers are checked against USD-equivalent limits (see decisions). Fraud rules are not
  changed in this intent.
- **notification**: `TransferEvent` and `TransferNotificationListener` carry the amount in the right currency instead
  of kobo and `Money.format`.
- **Web page**: send screen with currency, recipient lookup, quote and countdown.
- **Database**: new migrations (`V14` onward; numbers assigned when a branch merges, rule D4 of Plan 001).

## Decisions made while brainstorming
- **Scope:** PayBridge customer to PayBridge customer, any currency. Payout to a bank abroad is **not** here; it moves
  to Intent 004, with Flutterwave as the chosen provider (switched from Fincra).
- **Fees:**
  - Same-currency transfers are **free**, always.
  - Cross-currency transfers are **free for the sender's first 30 days** from sign-up, then a **0.5%** fee on the FX
    step (the same schedule as conversions). The quote records the reason `PROMO` or `STANDARD`.
  - The existing **N50 stamp duty** on Naira transfers of N10,000 and above stays: it is a statutory charge, not a
    PayBridge fee. All existing Naira transfer behaviour and tests stay as they are.
- **Quotes:** only cross-currency transfers use a quote. Same-currency transfers keep the existing one-step flow with
  an `Idempotency-Key`, made currency-aware.
- **Recipient cannot receive the chosen currency (no wallet in it):** the transfer is refused. The lookup tells the
  sender which currencies are accepted. This reveals which currencies someone can receive; that is accepted.
- **Limits:** NGN-to-NGN transfers keep today's limits (N1,000,000 per transfer, N5,000,000 a day). Every other transfer
  uses a **USD-equivalent** limit from the same rate snapshot: **$1,000 per transfer and $5,000 per day**
  (placeholders). If rates are stale, these transfers are refused with a clear message; NGN-to-NGN is not affected.
- **Fraud rules:** unchanged for now. They stay in kobo and apply to Naira transfers only.
- **Notifications:** the sender and the recipient are told when a transfer **succeeds**; the sender is told when a
  confirmed transfer **fails**. A failed cross-currency transfer leaves balances unchanged, and the message says so (it
  does not say "money returned"). A request refused before confirming (a limit, an unsupported currency) is answered on
  screen and does not create a notification. This is my reading of "failed and successful transaction"; confirm it.
- **Rate risk:** with the free period and a 30-minute lock, PayBridge carries the rate risk (Spec 001, concern E).
  This is accepted in test mode. It must be revisited before any real money.
- **Money type:** `_kobo` becomes `_minor` on `transfers`, with a `currency` column. Charge settings and fraud
  thresholds keep `_kobo` where they are Naira-only.

## Constraints
- Money is whole minor units (`long`); rates and decimals are `BigDecimal` at the edge only.
- Every balance change writes balanced ledger lines **per currency** in the same transaction.
- Lock the sender's and recipient's accounts, then the wallets, in the one agreed order. Do not hold a transaction open
  during the rates call (call `RateApi.rate()` before the transaction).
- A quote's rate and fee never change after confirm (R-QUO-4 to R-QUO-6 of Spec 001).
- Flyway owns the schema; never edit an applied migration. `ddl-auto` stays `validate`. `audit_log` and
  `ledger_entries` stay append-only.
- Modules talk only through `api/` packages. Notifications keep reading events and never call back into `transfer`.
- A notification can never slow down or fail a transfer (they stay after-commit and asynchronous).
- Do not break local NGN transfers, Paystack payouts or the existing tests. Changes to existing tests are listed in the
  plan, as in Plan 001.
- Test mode, free-tier hosting, no real money; tests never call a real external service.

## Risks and areas of concern
- **Regulation (verify before relying on any of this).** The CBN's revised IMTO guidelines (released 31 January 2024)
  are reported to limit licensed international money transfer operators to **inbound** transfers paid in naira. A
  Nigerian customer sending naira to a PayBridge customer in another country through a cross-currency transfer may
  look like an outbound transfer. These are secondary sources and the CBN circulars must be read. This is not legal
  advice. See open question 1.
- **Transfers between currencies are two-currency postings.** They must balance in each currency on their own, exactly
  as a conversion does.
- **Existing data shape.** `transfers.destination_account_number` is 10 characters and Naira-oriented. A recipient's
  account may hold several wallets.
- **Stale rates stop non-Naira transfers.** The price of using USD-equivalent limits is that transfers in other
  currencies pause when the snapshot is too old.
- **The lookup shows which currencies someone can receive.** Accepted for now.
- **Free transfers can be used to move value cheaply.** Fraud rules are not extended in this intent, so a non-Naira
  transfer is limited only by the USD-equivalent caps.

## Open questions
1. Does a cross-currency transfer between a Nigerian customer and a customer abroad need a **corridor rule** (an
   on and off table of which country pairs may send to which)? This was raised and not decided. It decides whether
   Nigeria-outbound transfers are switched off, and it also applies to Intent 004.
2. Do transfer limits and conversion limits share the same **daily USD-equivalent total**?
3. Should the quote for a cross-currency transfer show the recipient's name before the sender confirms?
4. Is the **sender's** sign-up date what decides the 30 free days (the proposal), and does the recipient's age matter?
5. Which wallet is debited if the sender has two wallets that could be the source? (The sender picks one; confirm.)

## Out of scope
- Payout to a bank account abroad and inbound remittance to a Nigerian bank (**Intent 004**).
- Settlement with partners, statements and sweeps (**Intent 003**).
- Changes to fraud rules (they stay as they are; revisit later).
- An FX margin on transfer quotes.
- Card, mobile-money or cash collection.
- An in-app way to change the home country.
- Admin and customer dashboards, email and SMS, HTTP rate limiting, monitoring (later intents, as in Intent 001).
