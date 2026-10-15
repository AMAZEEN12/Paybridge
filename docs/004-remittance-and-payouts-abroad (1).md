# Intent 004: Remittance and payouts abroad

Status: draft (written after the brainstorm)      Date: 2026-10-15      Author: Nwafor Blessing
Follows: Intent 001 (wallets, conversion) and Intent 002 (currency-aware transfers, the transfer quote)
Built side by side with: Intent 003 (settlement)

## The problem (in your own words)
PayBridge was described from the start as local transfer, cross-border settlement and **USD to NGN remittance**. Local
transfers exist, wallets and conversion exist, and Intent 002 lets customers pay each other in any currency. But a
customer still cannot pay **a bank account**, in any country, in any currency other than local Naira through Paystack.
A customer abroad cannot send money to a family member's Nigerian bank account in naira. A Nigerian customer cannot
send money to a bank abroad. The `remittance` module is an empty scaffold.

## The outcome we want
- A PayBridge customer can pay a **bank account** through **Flutterwave**, in three ways:
  1. **Remittance to Nigeria:** a customer with a foreign wallet (for example USD, GBP or EUR) pays a Nigerian bank
     account in naira. The recipient does not need a PayBridge account.
  2. **Payout abroad from a foreign wallet:** for example a UK customer pays a UK bank in GBP.
  3. **Payout abroad from an NGN wallet:** naira is converted and sent to a bank in another country (the
     Nigeria-outbound corridor).
- Before paying, the sender gives the bank details and a **purpose of transfer** from a list, and gets a **transfer
  quote** (Intent 002): the rate, the fee and the **exact amount the recipient's bank will receive**. The quote is
  locked for 30 minutes and can be used once.
- On confirm, the sender is debited, the payout is sent, and the sender can follow the status: `PENDING`,
  `SUCCESSFUL` or `FAILED`.
- If the payout fails, the sender is **refunded in full in the currency they paid**, at once. (Retries exist only if
  Flutterwave's sandbox shows a state that needs them; they are off by default.)
- Every step is traceable: audit log, ledger journal, quote and payout reference linked together, and the payout
  appears in the settlement batch for its day (Intent 003).
- Local NGN-to-NGN payouts keep going through **Paystack**, unchanged.

A non-programmer can check this by registering a customer in the UK (GBP home), funding the GBP wallet with test money,
sending 100 GBP to a Nigerian bank account, seeing the quote in naira, confirming, and watching the status reach
successful with the GBP balance down by exactly the quote. Repeating it from a Nigerian customer to a UK bank account
shows the same screens, and switching the Nigeria-outbound rule off makes that second payment refuse with a clear
message.

## Who and what it touches
- **remittance** (the empty module): `domain` (a remittance record linking sender, quote, payout and status),
  `service` (orchestrates quote, debit, conversion, payout, retry and refund), `web`, `api`, `repository`, and a
  `client` package for the Flutterwave gateway behind an interface (with a fake for tests and local runs).
- **fx**: the `TRANSFER` quote from Intent 002, with a bank account as the destination instead of a PayBridge account.
  The `quotes` table already needs a destination for Intent 002; the two intents share that migration design.
- **ledger**: a currency-aware journal for the debit, the FX pool, the fee, the payout in flight, the payout completed
  and the refund. It must balance per currency at every step.
- **compliance**: USD-equivalent limits for payouts, a velocity rule, and the **corridor rule table**.
- **account**: debit and refund of the sender's wallet by currency.
- **transfer**: not changed. Paystack payouts stay as they are.
- **settlement** (Intent 003): Flutterwave payouts and the rate-difference position (when PayBridge carries a worse
  rate) become settlement positions.
- **notification**: payout events carry the right currency (see decisions).
- **customer**: the sender's country is stored; the recipient is a name and a bank account, not a customer.
- **Web page**: a payout screen with bank details, purpose, quote and countdown, and a status view.
- **External services**: Flutterwave, with a test key. The base URL is checked against the current API reference. The
  secret key and webhook secret come from environment variables and are never committed.
- **Database**: new migrations for the remittance record and the quote destination, plus the purposes list if stored.

## Decisions made while brainstorming
- **Scope:** all three flows above are in.
- **Sender:** always a PayBridge customer with a funded wallet. Collecting money (cards, bank transfer in the sender's
  country) is out of scope; the dev funding endpoint stands in for it.
- **Provider:** **Flutterwave** (switched from Fincra, whose sandbox URL gave no page) for anything that crosses a currency or a border, including the naira paid to a Nigerian
  bank in a remittance. **Paystack keeps local NGN-to-NGN payouts.** Both sit behind gateway interfaces.
- **Flutterwave's documented flow is the model:** verify the account (where the corridor allows it), optionally request
  a **rate**, create the transfer with our own `reference` (from the idempotency key), then follow it by webhook and by
  fetching the status. A timeout is not a failure: the status is re-queried before anything is
  refunded.
- **Corridor rule table:** one config-driven table decides which sender-country to destination-country pairs are
  allowed. **Nigeria-outbound is ON in test mode**, and a single config switch turns it off. The documents say plainly
  that a real operator would first need the CBN position confirmed (see Risks).
- **Rate lock:** if Flutterwave's API reference confirms its rate id locks the rate, the quote embeds that id and its
  expiry caps ours. If not, **PayBridge locks its own quote** for 30 minutes and **carries the difference** if
  Flutterwave's real-time rate at payout time is worse. Until it is confirmed, PayBridge's own lock is the default.
- **Quote and fee:** the 30-minute `TRANSFER` quote from Intent 002. The fee is **1% of the amount with a minimum of $1
  equivalent** (placeholder). The minimum needs a fresh rate, so a payout is refused when rates are stale.
- **Limits:** **$1,000 per payout and $5,000 per day** (USD-equivalent placeholders, the same numbers as transfers).
- **Purpose of transfer:** **required for every payout**, chosen from a list held in config.
- **When a payout fails:** the sender is **refunded in full in the currency they paid**, at once, fee included. The
  earlier "retry up to 3 times from a destination wallet" rule came from Fincra's `isConversionSuccessful` behaviour.
  Nothing like it was found in Flutterwave's documents, so retries stay **off** unless the sandbox shows a state where
  money is converted but not paid.
- **Webhooks** are checked before anything happens. The page read for Flutterwave describes a `verif-hash` header
  compared with a secret we choose, which may be an older scheme, so the current API reference decides. A wrongly signed
  call is refused, and the outcome is confirmed by a verify call before money moves.
- **Notifications:** carry over the payout notifications that exist today (accepted, successful, failed and refunded),
  made currency-aware. Confirm this reading in the spec.

## Constraints
- Money is whole minor units (`long`); rates and decimals are `BigDecimal` at the edge only. Use the currency table for
  decimal places; never assume 2.
- Every balance change and ledger posting is in one transaction per step, balanced per currency.
- **No database transaction is open during a Flutterwave call.** Reserve and commit, then call, then settle.
- Every request needs an `Idempotency-Key`; the same key and request returns the stored result. Our transfer `reference`
  is derived from it so a retry can never pay twice.
- A confirmed quote's rate and fee do not change (R-QUO-4 to R-QUO-6 of Spec 001).
- Never log secrets, full account numbers or the webhook secret. Account numbers are masked, as they are today.
- Flyway owns the schema; never edit an applied migration. `ledger_entries` and `audit_log` stay append-only.
- Modules talk only through `api/` packages.
- Tests never call Flutterwave. A fake gateway behaves by pattern, like the fake Paystack one, to cover success, rejection,
  timeout, and (only if retries are enabled) conversion success with payout failure.
- Test mode, free-tier hosting, no real money.

## Risks and areas of concern
- **Regulation (verify before relying on any of this).**
  - The CBN's revised IMTO guidelines (released 31 January 2024) are reported to limit licensed money transfer
    operators to **inbound** transfers paid in **naira**, with amounts above about $200 equivalent paid into a bank
    account. One explainer says the Naira-only and settlement-account rules take effect on 1 May 2026.
  - A Substack summary citing Nairametrics says banks and fintechs were separately barred from international transfer
    services; the explainer says fintechs may act only as agents of licensed operators.
  - Switching Nigeria-outbound ON in test mode is a learning choice, not a statement that it is allowed. These are
    secondary sources; the CBN circulars must be read. This is not legal advice.
- **The Flutterwave sandbox is not confirmed for this.** Its documents describe international payouts, mock bank
  transfers and simulated webhooks, but the pages I could read do not say cross-currency payouts behave in the sandbox,
  and account resolve may work for Nigerian accounts only. The fake gateway is the fallback.
- **No known rate lock.** Flutterwave describes real-time rates and an optional rate lookup, with no lock or expiry. If
  the rate id does not lock, PayBridge carries any worse rate for up to 30 minutes on every payout.
- **Two payments, not one.** Debiting the sender and paying the recipient happen at different times and can end
  differently. The refund, retry and "unknown outcome" paths decide whether the ledger stays correct.
- **The sender waits for the outcome** with their money already out of their wallet, until the webhook, a verify call
  or the scheduled job settles it. A timeout is never refunded.
- **Rate exposure.** The 30-minute lock with a 1% fee may not cover a rate move or Flutterwave's real cost, which has not
  been seen. Test mode makes this acceptable for now.
- **Bank details differ by country** (IBAN, sort code, routing number, SWIFT). The wrong field is the most likely
  failure.
- **Fee versus competitiveness.** The World Bank's average cost of sending $200 to sub-Saharan Africa was 7.9% (Q2 2023),
  against a 3% target for 2030. 1% is far below the average and may be below provider cost.
- **Stale rates stop payouts**, because both the limits and the $1 minimum need a rate.

## Open questions
1. What bank details does each destination country need, and which does Flutterwave require for the corridors we support?
2. Can Flutterwave verify an account abroad before the payout, or only Nigerian accounts?
3. What are the purpose values (family support, education, and so on)?
4. How long between retries, if retries are ever switched on?
5. What does the Flutterwave sandbox actually support: cross-currency payouts, NGN-sourced payouts, which destination
   currencies, webhooks? (Check with a test account before the spec.)
6. Which currencies can PayBridge pay out in at the start? Flutterwave's docs show USD to AUD, EUR and NGN as
   examples; the supported-currency list of Intent 001 is longer.
7. Does the same corridor rule table also govern PayBridge-to-PayBridge transfers across borders (Intent 002, open
   question 1)?
8. Are saved recipients (beneficiaries) in this intent or later?
9. If retries are ever switched on, what does the sender see while a payout retries: `PENDING` only, or a note?
10. In which currency is the fee recorded and swept (Intent 003): the sender's currency?
11. Does Flutterwave's rate id lock the rate, and for how long? Can a transfer name the exact destination amount?

## Out of scope
- Collecting money from the sender (cards, bank transfer, mobile money) and any real funding.
- Cash pickup, mobile-money wallets and domiciliary accounts.
- Guest senders without an account.
- Replacing Paystack for local NGN-to-NGN payouts.
- Settlement itself (**Intent 003**): partner statements, batches and sweeps.
- Admin and customer dashboards, email and SMS, HTTP rate limiting, monitoring.
- Real money, live Flutterwave credentials, and any licensing or KYB work.
