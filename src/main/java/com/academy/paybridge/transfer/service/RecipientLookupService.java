package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.shared.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * "Who am I paying, and which currencies can they receive?" (R-LKP). Read-only: it changes nothing and
 * never reveals balances, wallet status or the owner's id. It shares the name-enquiry limiter, because
 * both let a caller learn who owns an account number (S4, R-VER-7).
 */
@Service
public class RecipientLookupService {

    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("\\d{10}");

    public record Recipient(String accountNumber, String accountName, List<String> receivableCurrencies) {
    }

    private final AccountApi accounts;
    private final CustomerApi customers;
    private final ResolveRateLimiter limiter;

    public RecipientLookupService(AccountApi accounts, CustomerApi customers, ResolveRateLimiter limiter) {
        this.accounts = accounts;
        this.customers = customers;
        this.limiter = limiter;
    }

    public Recipient lookup(Long customerId, String accountNumber) {
        limiter.check(customerId);                       // first, so even a wrong guess uses up an attempt
        if (accountNumber == null || !ACCOUNT_NUMBER.matcher(accountNumber).matches()) {
            throw notFound();                            // same answer as an unknown number: nothing to learn
        }
        Long ownerId = accounts.ownerOf(accountNumber).orElseThrow(RecipientLookupService::notFound);
        String name = customers.get(ownerId).fullName();
        return new Recipient(accountNumber, name, accounts.receivableCurrencies(accountNumber));
    }

    private static ApiException notFound() {
        return ApiException.notFound("ACCOUNT_NOT_FOUND", "We could not find that account.");
    }
}