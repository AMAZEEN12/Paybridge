# Plan 001: Multi-currency wallets and conversion

Status: in progress      Date: 2026-10-13      Author: Nwafor Blessing
Implements: `spec.md` (from `001-multi-currency-wallets.md`)

## Progress (last updated 2026-10-13)

| Step | What | Status |
|------|------|--------|
| 0 | Baseline and guardrails | Build green. Hooks self-test not confirmed. |
| 1 | Wallets, NGN only | Code done, all four commits green (V8 reset + ledger columns; V9 wallets + account module; ledger wallet keys). Left: hand check on the web page, review, deploy. |
| 2 | Currencies and per-currency ledger | Built (branch step-2-currency-fix). V10 country and currency lists still to verify before release.  |
| 3 | Sign-up with country and the single account | Not started |
| 4 | Swap, park and receive-only wallets | Code done (merged). Left: hand check on the web page, review, deploy. |
| 5 | Rates (parallel track) | Not started |
| 6 | Quotes and conversion | Not started |
| 7 | Convert screen, clean-up and release | Not started |

This plan was drafted from a read of the code and should be checked in Claude Code plan mode against the real
repository before the first step starts. Any step that departs from this plan updates this file in the same commit.

---

## 1. Approach

1. **Refactor first, with no new behavior.** Step 1 moves balances into wallets with only NGN in use. The existing test
   suite is the proof that nothing broke.
2. **A compatibility shim carries the old callers.** `AccountApi.debit/credit(accountNumber, kobo)` and the `LedgerApi`
   methods keep their signatures and act on the account's NGN wallet. Currency-aware methods are added beside them.
   The shim is removed in step 7, once every caller has moved.
3. **Small steps, each ending green and committed.** Every step ends with `mvn test` passing (with the `paybridge_test`
   database present), no edits to `V1` to `V7`, and this file updated.
4. **The rates track runs in parallel** (step 5). It adds the `fx` package only and touches no existing code, so it is
   built in its own Claude Code session on its own git worktree.

```
Step 0 baseline
   |
Step 1 wallets (NGN only) --> Step 2 currencies --> Step 3 sign-up --> Step 4 swap/park --+
                                                                                          +--> Step 6 conversion --> Step 7 UI, cleanup, release
Step 5 rates (parallel, own worktree) ----------------------------------------------------+
```

---

## 2. Decisions this plan makes (and deviations from the spec)

| # | Decision | Effect on the spec |
|---|----------|--------------------|
| D1 | `_kobo` becomes `_minor` only on the **new wallet table and `ledger_entries`**. The Naira-only `transfers` table, charge config and fraud thresholds keep `_kobo` until Intent 002. | Narrows R-MON-1. Spec updated to match. |
| D2 | The existing "up to 5 accounts per customer" behavior stays until **step 3**, where sign-up starts creating the single account. Step 1 changes no behavior. | Clarifies R-WAL-1 timing. |
| D3 | Conversion is behind a switch (`paybridge.conversion.enabled`, default `false`) until step 7, so a half-finished feature is never live. | New; implements "never live half-built". |
| D4 | Migration numbers are assigned **when a branch merges**, never reserved ahead. A lower-numbered migration must never merge after a higher one has been deployed (Flyway would refuse to start). | New rule. |
| D5 | The currency-aware money type is a new class (suggested: `Money`-style record holding minor units and a currency, plus a `CurrencyRegistry` loaded from the `currencies` table). The existing static `Money` stays as the NGN helper until step 7. The exact names are settled in plan mode. | Implements R-MON-2 to R-MON-4. |
| D6 | Deploy after steps 1, 4 and 7. Steps 2, 3 and 5 add nothing a user can see (step 3 changes sign-up only). Nothing on the live app needs to survive the reset. | Implements spec concern A. |

Suggested migration order (numbers are indicative; see D4): V8 reset and ledger changes, V9 wallets, V10 currencies and
countries, V11 customer country, then the rates snapshot table, then quotes and conversions.

---

## 3. Step 0: Baseline and guardrails

**Goal:** know the starting point is green and make the protected files hard to damage.

- Create the `paybridge_test` database; run `mvn test`; record that all tests pass (the end-to-end tests really run).
- Create a branch per step (`step-1-wallets`, and so on) and work in small commits.
- Install the guardrails (`.claude/settings.json`, `.claude/hooks/`, `.gitattributes`), run
  `bash .claude/hooks/selftest.sh` and confirm every case passes on your machine.
- Add `OXR_APP_ID` to `.env.example`, the README variable table and `render.yaml` (`sync: false`), plus
  `PAYBRIDGE_CONVERSION_ENABLED` (default `false`). The geo-IP setting is added in step 3.

**Done when:** baseline test output is saved in the PR description and the self-test passes (including the block on
editing `V3__transfers.sql`).

---

## 4. Step 1: Wallets, NGN only (the risky step)

**Goal:** balances live in wallets; everything works exactly as before.

**Migrations**
- **V8:** one-time reset (clear customers, accounts, transfers, ledger entries, audit log, notifications and the
  compliance tables, restarting identities); rename `ledger_entries.amount_kobo` to `amount_minor`; add
  `ledger_entries.currency` (not null). `TRUNCATE` does not fire the row-level append-only trigger; confirm this in
  a test. Re-create the three system accounts.
- **V9:** `wallets` (account_id, currency, balance_minor `CHECK >= 0`, status, is_home, version, created_at;
  `UNIQUE (account_id, currency)`); remove `balance_kobo` from `accounts`; give each system account an NGN wallet.

**Code (module: file)**
- `account`: `Account` (drop balance), new `Wallet` entity and `WalletRepository`; `AccountService` (open account
  creates the NGN home wallet; `debit`/`credit`/`lockInOrder`/`balances()` operate on the NGN wallet; locking order is
  unchanged: sorted account numbers); `AccountView`, `AccountDtos`, `AccountController` (balance read from the wallet);
  `DevFundingController`.
- `ledger`: `LedgerEntry` (`amountMinor`, `currency`), `LedgerEntryRepository`, `LedgerService` (a single helper turns
  an account number into the wallet key `<account_number>:NGN` and writes `currency = NGN`; the `EXT:` accounts keep
  their names), `ReconciliationService`/`ReconciliationRules` (compare wallet keys to wallet balances).
- Unchanged by design: `TransferService`, `TransferSettlementService`, `ChargeCalculator`, fraud rules,
  notifications. They still call the old method signatures.

**Tests that prove it** (start this step with `PAYBRIDGE_FREEZE_TESTS=1` so existing tests cannot be edited)
- The **entire existing suite passes with no change to its assertions** (`PayBridgeFlowTest`, `ReconciliationRulesTest`,
  `FraudRulesTest`, `ChargeCalculatorTest`, `MoneyTest`, `WebhookSignatureTest`). Fixture changes only: check
  `TestDb`'s clean-up list for the new table.
- New: the V8 reset runs on an empty database and on one with old-style data; `TRUNCATE` works while `UPDATE` and
  `DELETE` on `ledger_entries` are still refused; opening an account creates exactly one NGN home wallet; ledger keys
  end in `:NGN`; reconciliation is clean after the 20-parallel-transfers test.

**Done when:** all of the above pass, the web page works by hand (register, open account, add test money, send,
payout to the fake bank), and a PR review confirms no module bypasses an `api/` package. **Deploy.**

**Commit sequence (as built).** Four small commits on `step-1-wallets`, each meant to leave the suite green:
(1) V8 reset + ledger columns, (2) V9 wallets, (3) account module, (4) ledger module.
*Departure, commit 1:* renaming `ledger_entries.amount_kobo` in V8 breaks the `LedgerEntry` mapping immediately
(`ddl-auto: validate`), so commit 1 also carries the minimal ledger mapping change: `LedgerEntry`
(`amountMinor`, `currency`), the three column names in `LedgerEntryRepository`, and `LedgerService` passing `"NGN"`.
Commit 4 keeps the rest of the ledger work (wallet keys `<account_number>:NGN`, reconciliation, messages).
*Why V8's tests run in a scratch schema:* `V8MigrationTest` builds V1 to V7, adds old-style data and runs V8 in a
throwaway schema, so the shared test database is never reset by the test itself.

*Departure, commits 2 and 3 merged:* dropping `accounts.balance_kobo` in V9 breaks the `Account` mapping at once,
so V9, `Wallet`, `WalletStatus`, `WalletRepository` and the `Account`/`AccountService` changes are one commit.
`AccountApi.balances()` kept plain account-number keys until the ledger commit switched both sides to
`<account_number>:NGN`. Locking: `lockInOrder`, `debit` and `credit` lock the NGN wallet row (sorted account
numbers); `setFrozen` still locks the account row after taking the wallet lock, so a freeze waits for a transfer in
flight. V9 copies existing balances into the wallets, so it is safe on a database that already holds money.
*Departure, commit 4:* `walletKey` and `accountNumberOf` live on `LedgerApi` (not private in `LedgerService`), so
`AccountApi.balances()` and the ledger share one definition of the `<account_number>:NGN` key. `LedgerService.post`
writes the wallet key and `currency = NGN`; `ReconciliationRules` strips the suffix before `Money.maskAccount`.
`AccountApi`, `AccountRepository`, `AccountView`, the DTOs, controllers and `app.js` did not change.
*Tests added in step 1 (no existing assertion changed):* `V8MigrationTest`, `WalletsV9MigrationTest`,
`AccountWalletsTest`, `ReconciliationWalletKeysTest`, `LedgerWalletKeysTest`.
*Lesson:* the test database survives between runs and the ledger is append-only, so rows written under an older key
shape make reconciliation fail. After a change to the ledger key shape, drop and recreate the test database.
Deploy a step as a whole, never one commit at a time.

**Risks:** hidden callers reading `balance_kobo` directly (grep for it before starting; spec concern B); the
reconciliation job comparing keys of different shapes; the payout reserve/refund path, which writes through
`EXT:IN_FLIGHT`.

---

## 5. Step 2: Currencies and per-currency ledger (invisible to users)

**Goal:** the system knows currencies and the ledger balances per currency.

- **Migration V10:** `currencies` (code, name, symbol, minor_units, enabled) and `countries` (code, name,
  currency_code), seeded with the 50 currencies in spec section 9 and the country mapping. The exclusion list is
  config (`paybridge.currencies.excluded`).
- **Code:** `shared/money` gets the currency-aware money type and `CurrencyRegistry` (D5), with `BigDecimal`
  helpers only (no `double`); `Money.format` for non-NGN currencies uses the registry. `LedgerApi` gains currency-aware
  journal methods; `ReconciliationRules` and `ReconciliationService` group journals and wallets by currency.
- **Tests:** `MoneyTest` for 0-, 2- and 3-decimal currencies (JPY, USD, KWD); a mixed-currency addition is refused;
  an excluded currency is rejected by the registry; `ReconciliationRulesTest` with a four-line, two-currency journal
  balances, and with an unbalanced second currency fails.

**Done when:** acceptance criterion 8 passes at unit level and nothing user-visible changed.


**As built (departures from the text above).**
- *Names:* the money type is `CurrencyAmount` (record: `long minor`, `CurrencyInfo`), with `CurrencyInfo` and `CurrencyRegistry`,
  all in `shared/money`. The static `Money` is untouched and stays the Naira helper until step 7.
- *Registry loader:* `shared/config/CurrencyConfig` builds the registry from the `currencies` table at start-up (D5 said the
  loader would live in `account`; `shared` is simpler and needs no module). Exclusion list: `paybridge.currencies.excluded`
  in `application.yml` (comma separated, env `PAYBRIDGE_CURRENCIES_EXCLUDED`).
- *`Money.format` for non-NGN currencies:* not changed. `CurrencyAmount.format()` and `CurrencyRegistry.format()` do it.
- *Migration:* `V10__currencies_countries.sql` (50 currencies from spec section 9, 82 countries). Symbols use Postgres
  unicode escapes. The spec's prose says "five zero-decimal currencies" but its list marks eight (JPY, UGX, RWF, XOF, XAF,
  CLP, KRW, VND); the list is what is seeded. The country list is a starting mapping and must be reviewed (R-CUR-4).
- *Ledger:* `LedgerApi.recordJournal(key, reference, type, List<JournalLine>)` posts any mix of currencies and refuses a
  journal in which any currency does not balance on its own, or a wallet key whose suffix differs from the line's currency.
  The Naira methods are unchanged and now go through the same code.
- *Reconciliation:* `ReconciliationRules.checkByCurrency` (new) runs the existing checks once per currency; `check` is
  unchanged. The money-in rule becomes `held + in flight + paid out + FX pool/fee = funded` (the FX term is zero until
  step 6). `ReconciliationReport` gains `currencies` (per-currency totals); the `*Kobo` fields stay the Naira totals;
  problems for another currency start with `[USD]`. New repository queries group by currency.
- *Trap for step 4:* `AccountApi.balances()` still returns NGN wallets only. Once other currencies have wallets it must
  return every wallet, or reconciliation reports them as "unknown account".
- *Tests added (no existing test edited):* `CurrencyAmountTest`, `CurrencyRegistryTest`, `LedgerJournalTest`,
  `ReconciliationByCurrencyTest`, `MultiCurrencyLedgerTest`. The last one writes USD/EUR journals on `EXT:` accounts into
  the test database; they reconcile cleanly and the ledger is append-only, so they stay there.

---

## 6. Step 3: Sign-up with country and the single account

**Goal:** a new customer gets one account with the right wallets.

- **Decision needed before starting (open item 1):** the geo-IP provider. The `GeoLocator` interface, the
  production implementation, the test override (disabled in the production profile) and the correct forwarded-IP
  header behind Render are built in this step.
- **Migration V11:** `customers.country` (not null).
- **Code:** `customer` (registration detects and stores the country, rejects unsupported or excluded countries
  without creating anything), `account` (a new operation creates the single account with the home wallet plus
  USD, EUR and GBP, skipping the home currency, and leaves the free slot when the home is USD/EUR/GBP; the "up to 5
  accounts" rule is removed and the manual open-account action goes away or returns the existing account),
  `WebHardeningFilter` (client IP if needed), `index.html` and `app.js` (sign-up shows the blocked-country message
  and the detected country).
- **Tests:** acceptance 2 and 7 (USD home gets 3 wallets and a free slot; blocked sign-up leaves no customer or
  account); a Nigerian sign-up gets NGN, USD, EUR, GBP; the override is refused in the production profile; the geo
  provider is stubbed in every test.

**Done when:** the existing flow tests are updated for sign-up-creates-account and pass.


**As built (departures from the text above).**
- *Geo-IP provider (open item 1, resolved):* freeipapi.com free server, `GET https://free.freeipapi.com/api/v1/json/{ip}`,
  no key, 10 requests per 10 seconds and 60 per minute per server IP. Behind `GeoLocator`; any failure refuses the
  sign-up with 503 `COUNTRY_UNDETECTED`. The IP is never logged. It is sent to a third party, so say so in the privacy text.
- *Client IP behind Render:* read from the `True-Client-IP` header (`paybridge.geo.client-ip-header`). Render appends
  to `X-Forwarded-For` without stripping client-supplied values, so its first entry can be forged and is NOT used. If the
  header is configured and missing, the peer address is not used either. Must be verified on the live app (see below).
- *Override (R-CTY-4):* this app has no production profile. `paybridge.geo.override-country` only works when
  `paybridge.geo.allow-override=true`, and the app refuses to start if an override is set without it. Render never sets it.
- *No `WebHardeningFilter` change:* the controller reads the header.
- *Module wiring:* `customer.service.RegistrationService` detects and checks the country (no transaction open), then
  `AuthService.register` creates the customer and calls `AccountApi.openHomeAccount` in one transaction. `CountryRegistry`
  lives in `shared/money` next to `CurrencyRegistry`.
- *Open-account endpoint:* `POST /api/v1/accounts` now returns the existing account (200). The 5-account rule and the
  web button are removed. The one-account rule is enforced in the service, not by a unique index, so a database that
  already holds multi-account test users still starts.
- *V11:* adds `customers.country`; customers from before this migration are set to NG.
- *Step 4 trap closed here:* `AccountApi.balances()` returns every wallet keyed `<account_number>:<currency>`.
- *Cleanup:* `LedgerEntry.getAmountKobo()` alias removed.
- *Known gap until step 4:* `debit`, `credit` and `lockInOrder` only handle the NGN wallet, so a customer with a
  non-NGN home currency cannot yet send or receive local transfers. The page shows ₦0.00 for them.
- *Tests:* `ClientCountryResolverTest`, `CountryRegistryTest`, `FreeIpApiGeoLocatorTest`, `SignUpCountriesTest`;
  `AccountsWalletsTest` rewritten; one status assertion in `PayBridgeFlowTest.newUser` changed from 201 to 200.
---

## 7. Step 4: Swap, park and receive-only wallets

**Goal:** customers can manage which currencies are active. **Deploy after this step.**

- **Code:** `account` (wallet service: swap, park, reactivate, cap of 4 enforced under the account row lock; the
  currency-aware `debit`/`credit` refuse a parked wallet for debits and allow credits; frozen account blocks all
  debits and swaps; audit-log entries), endpoints `GET /api/v1/currencies`, `GET /api/v1/wallets`,
  `POST /api/v1/wallets/swap`; `DevFundingController` takes a currency and credits `EXT:FUNDING` in that currency
  (parked wallets allowed); in-app notice when money lands in a parked wallet if the existing notification module can
  carry it (open item 3, otherwise deferred and noted here); `index.html`/`app.js` wallet list and swap control.
- **Tests:** acceptance 3, 4, 5 and 6; two simultaneous swaps never exceed 4 active wallets; swapping to an excluded
  or unsupported currency is refused; funding a parked wallet works and sending from it does not.

**Done when:** the wallet list and swap work by hand with test money in several currencies.

---

## 8. Step 5: Rates (parallel track, own worktree)

**Goal:** the app holds a fresh, stored rate snapshot and knows when it is stale.

- **Code (module `fx`):** `api/RateProvider` and a snapshot service API; `client/OpenExchangeRatesClient` (USD base,
  supported codes only, `OXR_APP_ID` from the environment); `domain/RateSnapshot` and its repository; a service that
  loads the latest snapshot at start-up, refreshes hourly, supports a single-flight on-demand refresh at most once
  every 10 minutes, backs off after failures, checks the free usage endpoint and stops at 95% of the monthly quota;
  `isStale()` against `max-snapshot-age` (default 3 hours); cross-rate derivation `R[B] / R[A]` with 12 decimal
  places; a stub provider for tests and local runs. Snapshot table migration gets the next free number (D4).
- **Until step 2 merges,** the supported-currency list is a constructor argument, not read from the table.
- **Tests:** cross-rate maths; staleness boundary; no real network calls in the suite; start-up does not call the API
  when the stored snapshot is fresh; back-off and quota-stop with the stub; acceptance 15 at service level.

**Done when:** a manual run against the real provider (one call) stores a snapshot with NGN present, and the suite
passes with the network disabled.

---

## 9. Step 6: Quotes and conversion (needs steps 4 and 5)

**Goal:** a customer can convert between active wallets with a fee, limits and a locked quote.

- **Migration (next free number):** `quotes` and the conversion link.
- **Code:** `fx` (`QuoteService`: server-side quote with fee and rounding per R-CONV-2, single-use confirm by
  conditional update, 60-second expiry, active-quote cap, stuck-`EXECUTING` clean-up job; `ConversionService`: one
  transaction that locks the account then both wallets in sorted order, re-checks balance, status and limits, writes the
  journal from spec 7.2, completes the quote; failure marks the quote `FAILED`), `ledger` (`recordConversion` with the
  `EXT:FX_POOL:<CCY>` and `EXT:FX_FEE:<CCY>` accounts), `compliance` (conversion limits in USD-equivalent and the
  velocity rule through its `api/`), `account` (currency-aware wallet debit and credit used by conversion), the
  endpoints `POST /api/v1/conversions/quotes` and `POST /api/v1/conversions`, all behind
  `paybridge.conversion.enabled` (D3). A `Clock` is injected so tests control time.
- **Tests:** acceptance 1 and 9 to 15. Unit: fee and rounding (`ceil` fee, `floor` payout, zero-decimal target),
  zero-target refusal, fee window at day 29 and day 30. Concurrency: two simultaneous confirms of one quote convert
  once; opposite-direction conversions between the same two wallets do not deadlock; insufficient balance at confirm
  time fails cleanly. Reconciliation is clean after every test.

**Done when:** conversions work end to end with the stub provider, the journal balances per currency, and the switch
is still off in the default configuration.

---

## 10. Step 7: Convert screen, clean-up and release

**Goal:** finish the feature and remove the temporary scaffolding. **Deploy after this step.**

- `index.html`/`app.js`: convert screen (source, target, amount, quote with rate, fee, exact amount received, 60-second
  countdown, new quote on expiry), "indicative rate" labels, fee shown including a waived fee.
- **Remove the shim:** move every remaining caller of the old `AccountApi`/`LedgerApi` methods to the currency-aware
  ones, then delete the old methods and the NGN-only `Money` helpers that are no longer used.
- Update `README.md` (module table: ledger and fx built; new environment variables; new tests), `CLAUDE.md` (current
  work and "things Claude gets wrong"), and the statuses of `intent` and `spec`.
- Run all 16 acceptance criteria by hand and record the results in the PR. Turn the conversion switch on in Render.

**Done when:** the non-programmer test passes on the deployed app.

---

## 11. Risks and how the plan answers them

| Risk | Where | Response |
|------|-------|----------|
| Hidden code reads or writes balances directly | Step 1 | Grep for `balance`/`kobo` before starting; the shim; the unchanged test suite as proof. |
| Deadlock between conversions, swaps and transfers | Steps 4 and 6 | One lock order everywhere: account row, then wallets sorted by id; opposite-direction concurrency test. |
| Reset wipes the database unexpectedly | Step 1 | Confirmed that nothing on the live app needs to survive; the migration is documented and tested on empty and seeded databases. |
| Free-tier rate quota burned by restarts or retries | Step 5 | Load from the database at start-up, back-off, stop at 95%, stub in tests. |
| Migration order conflict between parallel branches | Steps 1 to 6 | Assign numbers at merge time (D4); merge in version order. |
| Half-finished conversion reaches users | Steps 6 and 7 | Config switch off by default until step 7. |
| Geo-IP provider undecided blocks sign-up work | Step 3 | Decide before step 3 starts; interface plus stub so tests never depend on it. |

---

## 12. Proof of done

The 16 acceptance criteria in `spec.md` section 10 map to steps as follows: 1 in step 6; 2 and 7 in step 3; 3 to 6 in
step 4; 8 in steps 2 and 6; 9 to 14 in step 6; 15 in steps 5 and 6; 16 in step 1. Every step also requires `mvn test`
green with `paybridge_test` present, with the output attached to the pull request.