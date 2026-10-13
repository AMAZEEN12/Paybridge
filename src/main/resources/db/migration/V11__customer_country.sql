-- Step 3 (plan 001): every customer has a country, detected once at sign-up from the request IP and never changed
-- through the app (R-CTY-1, R-CTY-3). It is the ISO 3166-1 alpha-2 code, for example NG.
-- Customers who signed up before this migration (test users from steps 1 and 2) are all given NG. They keep their
-- existing account; only new sign-ups get the USD, EUR and GBP wallets.
-- No foreign key to countries: a country can be removed from the countries table later without touching customers.
ALTER TABLE customers ADD COLUMN country VARCHAR(2) NOT NULL DEFAULT 'NG';
ALTER TABLE customers ALTER COLUMN country DROP DEFAULT;
ALTER TABLE customers ADD CONSTRAINT ck_customers_country CHECK (country ~ '^[A-Z]{2}$');