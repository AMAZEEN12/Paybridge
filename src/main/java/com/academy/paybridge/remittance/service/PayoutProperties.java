package com.academy.paybridge.remittance.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Everything under paybridge.payouts in application.yml. Secrets come from the environment and are never printed.
 * Quote country codes in YAML ("NG"): an unquoted NO (Norway) is read as the boolean false.
 */
@ConfigurationProperties(prefix = "paybridge.payouts")
public record PayoutProperties(
        String provider,
        Flutterwave flutterwave,
        Fee fee,
        Retry retry,
        List<String> purposes,
        List<Country> countries) {

    public PayoutProperties {
        provider = provider == null || provider.isBlank() ? "fake" : provider.trim().toLowerCase(Locale.ROOT);
        flutterwave = flutterwave == null
                ? new Flutterwave(null, null, null, 0, 0, null, null, 0, 0, null) : flutterwave;
        fee = fee == null ? new Fee(null, null) : fee;
        retry = retry == null ? new Retry(false, 0, null) : retry;
        purposes = purposes == null || purposes.isEmpty()
                ? List.of("FAMILY_SUPPORT", "EDUCATION", "MEDICAL", "RENT", "GIFT", "OTHER")
                : List.copyOf(purposes);
        countries = countries == null ? List.of() : List.copyOf(countries);
    }

    public record Flutterwave(
            String baseUrl,
            String secretKey,
            String webhookSecret,
            int connectTimeoutMs,
            int readTimeoutMs,
            String rateMode,
            String verifyMode,
            int verifyCacheHours,
            int verifyDailyLimit,
            List<String> verifiableCountries) {

        public Flutterwave {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.flutterwave.com/v3" : baseUrl;
            connectTimeoutMs = connectTimeoutMs <= 0 ? 3000 : connectTimeoutMs;
            readTimeoutMs = readTimeoutMs <= 0 ? 10000 : readTimeoutMs;
            rateMode = rateMode == null || rateMode.isBlank() ? "OWN_LOCK" : rateMode.trim().toUpperCase(Locale.ROOT);
            verifyMode = verifyMode == null || verifyMode.isBlank() ? "auto" : verifyMode.trim().toLowerCase(Locale.ROOT);
            verifyCacheHours = verifyCacheHours <= 0 ? 24 : verifyCacheHours;
            verifyDailyLimit = Math.max(0, verifyDailyLimit);                 // 0 = no counter
            verifiableCountries = verifiableCountries == null
                    ? List.of("NG")
                    : verifiableCountries.stream().map(c -> c.trim().toUpperCase(Locale.ROOT)).toList();
        }

        public boolean canVerify(String country) {
            return verifiableCountries.contains(country);
        }

        @Override
        public String toString() {
            return "Flutterwave[baseUrl=" + baseUrl + ", secretKey=***, webhookSecret=***, verifyMode=" + verifyMode + "]";
        }
    }

    /** Payout fee (R-PAY-4). Used from step B3. */
    public record Fee(BigDecimal rate, BigDecimal minimumUsd) {
        public Fee {
            rate = rate == null ? new BigDecimal("0.01") : rate;
            minimumUsd = minimumUsd == null ? BigDecimal.ONE : minimumUsd;
        }
    }

    /** Retries are off unless the sandbox shows converted-but-unpaid (S16). Used from step B5. */
    public record Retry(boolean enabled, int maxAttempts, Duration spacing) {
        public Retry {
            maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
            spacing = spacing == null ? Duration.ofMinutes(5) : spacing;
        }
    }

    /** A country payouts can go to, its currency, and the bank details it needs (R-PAY-2, R-PAY-16). */
    public record Country(String code, String name, String currency, List<BankField> bankFields) {
        public Country {
            code = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
            currency = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
            bankFields = bankFields == null ? List.of() : List.copyOf(bankFields);
        }
    }

    /** One detail a country needs. {@code name} is {@code bankCode}, {@code accountNumber}, or a key in bankFields. */
    public record BankField(String name, String label, boolean required) {
    }
}