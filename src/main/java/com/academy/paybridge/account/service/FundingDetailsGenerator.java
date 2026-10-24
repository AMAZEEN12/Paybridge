package com.academy.paybridge.account.service;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * SANDBOX ONLY. Makes up account details in the usual shape for each currency (valid ABA check digit, valid IBAN
 * check digits). In production a licensed partner bank issues these; nothing here is a real bank account.
 */
@Component
public class FundingDetailsGenerator {

    public record Generated(String bankName, String bankCountry, String accountNumber, String routingLabel,
                            String routingValue, String iban, String swiftBic) {
    }

    private final SecureRandom random = new SecureRandom();

    /** ngnAccountNumber is the customer's real PayBridge account number, used as-is for the Naira wallet. */
    public Generated generate(String currency, String ngnAccountNumber) {
        if ("NGN".equals(currency)) {
            return new Generated("PayBridge (simulated)", "NG", ngnAccountNumber, null, null, null, null);
        }
        if ("USD".equals(currency)) {
            return new Generated("Simulated Partner Bank USA", "US", digits(12),
                    "ABA routing number", abaRouting(), null, "SPBKUS33");
        }
        if ("GBP".equals(currency)) {
            String sort = digits(6);
            String account = digits(8);
            return new Generated("Simulated Partner Bank UK", "GB", account, "Sort code",
                    sort.substring(0, 2) + "-" + sort.substring(2, 4) + "-" + sort.substring(4),
                    iban("GB", "SPBK" + sort + account), "SPBKGB2L");
        }
        if ("EUR".equals(currency)) {
            String account = digits(10);
            return new Generated("Simulated Partner Bank Europe", "DE", account, null, null,
                    iban("DE", "10010010" + account), "SPBKDEFF");
        }
        String country = currency.substring(0, 2);
        return new Generated("Simulated Partner Bank (" + currency + ")", country, digits(10), null, null, null,
                "SPBK" + country + "22");
    }

    /** A short payment reference the sender can quote, for example PB7K2M9XQA. */
    public String reference() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("PB");
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String digits(int count) {
        StringBuilder sb = new StringBuilder();
        sb.append(1 + random.nextInt(9));
        for (int i = 1; i < count; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    /** 9 digits where 3(d1+d4+d7) + 7(d2+d5+d8) + (d3+d6+d9) is a multiple of 10. */
    private String abaRouting() {
        int[] d = new int[9];
        for (int i = 0; i < 8; i++) {
            d[i] = random.nextInt(10);
        }
        int sum = 3 * (d[0] + d[3] + d[6]) + 7 * (d[1] + d[4] + d[7]) + (d[2] + d[5]);
        d[8] = (10 - sum % 10) % 10;
        StringBuilder sb = new StringBuilder();
        for (int digit : d) {
            sb.append(digit);
        }
        return sb.toString();
    }

    /** IBAN check digits: move country + 00 behind the account part, turn letters into numbers, 98 minus mod 97. */
    private static String iban(String country, String bban) {
        String rearranged = bban + country + "00";
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            numeric.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(Character.getNumericValue(c)));
        }
        int mod = new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue();
        return country + String.format("%02d", 98 - mod) + bban;
    }
}