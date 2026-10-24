package com.academy.paybridge.customer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.customer.api.CustomerView;
import com.academy.paybridge.customer.domain.Customer;
import com.academy.paybridge.customer.domain.RefreshToken;
import com.academy.paybridge.customer.repository.CustomerRepository;
import com.academy.paybridge.customer.repository.RefreshTokenRepository;
import com.academy.paybridge.customer.web.AuthDtos.LoginResponse;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.config.AppProperties;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CountryInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final String GENERIC = "Email or password is incorrect.";
    private static final int REFRESH_TOKEN_DAYS = 30;

    private final CustomerRepository customers;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;
    private final AccountApi accounts;
    private final String dummyHash;

    public AuthService(CustomerRepository customers,
                       @Autowired(required = false) RefreshTokenRepository refreshTokens,
                       PasswordEncoder encoder,
                       JwtService jwt,
                       AuditService audit,
                       AppProperties props,
                       Clock clock,
                       AccountApi accounts) {
        this.customers = customers;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
        this.accounts = accounts;
        // Used to spend the same time when the email is unknown, so timing does not reveal who has an account.
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    /**
     * Creates the customer AND their single account in one transaction: if the account cannot be created, the
     * customer is rolled back too. The country has already been checked by RegistrationService.
     */
    @Transactional
    public CustomerView register(String fullName, String email, String password, CountryInfo country) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (customers.existsByEmail(normalized)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists.");
        }
        Customer saved;
        try {
            saved = customers.saveAndFlush(
                    new Customer(fullName.trim(), normalized, encoder.encode(password), country.code()));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists.");
        }
        audit.record("customer:" + saved.getId(), "CUSTOMER_REGISTERED", null, "country " + country.code());
        accounts.openHomeAccount(saved.getId(), country.currencyCode());
        return CustomerService.view(saved);
    }

    /** noRollbackFor: the failed-attempt counter must be saved even though we then throw. */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse login(String email, String password) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        Customer c = customers.findByEmail(normalized).orElse(null);
        Instant now = clock.instant();
        if (c == null) {
            encoder.matches(password == null ? "" : password, dummyHash);
            throw ApiException.unauthorized("INVALID_CREDENTIALS", GENERIC);
        }
        if (c.getLockedUntil() != null && c.getLockedUntil().isAfter(now)) {
            throw ApiException.unauthorized("ACCOUNT_LOCKED", "Too many failed attempts. Try again later.");
        }
        if (password == null || !encoder.matches(password, c.getPasswordHash())) {
            int attempts = c.getFailedLoginAttempts() + 1;
            if (attempts >= props.login().maxFailedAttempts()) {
                c.setLockedUntil(now.plus(props.login().lockMinutes(), ChronoUnit.MINUTES));
                c.setFailedLoginAttempts(0);
                audit.recordIndependently("customer:" + c.getId(), "LOGIN_LOCKED", null, "Too many failed logins");
            } else {
                c.setFailedLoginAttempts(attempts);
            }
            customers.save(c);
            throw ApiException.unauthorized("INVALID_CREDENTIALS", GENERIC);
        }
        c.setFailedLoginAttempts(0);
        c.setLockedUntil(null);
        customers.save(c);
        audit.record("customer:" + c.getId(), "LOGIN_SUCCEEDED", null, null);

        String refreshTokenRaw = issueRefreshToken(c.getId(), now);
        return new LoginResponse(jwt.issue(c), "Bearer", jwt.ttlSeconds(), refreshTokenRaw);
    }

    @Transactional
    public LoginResponse refresh(String refreshTokenRaw) {
        if (refreshTokenRaw == null || refreshTokenRaw.isBlank() || refreshTokens == null) {
            throw ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Session expired. Please sign in again.");
        }
        String hash = hashToken(refreshTokenRaw.trim());
        Instant now = clock.instant();

        RefreshToken rt = refreshTokens.findByTokenHash(hash)
                .filter(t -> t.isValid(now))
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Session expired. Please sign in again."));

        Customer c = customers.findById(rt.getCustomerId())
                .orElseThrow(() -> ApiException.unauthorized("CUSTOMER_NOT_FOUND", "Customer not found."));

        if (c.getLockedUntil() != null && c.getLockedUntil().isAfter(now)) {
            throw ApiException.unauthorized("ACCOUNT_LOCKED", "Account is locked.");
        }

        // Revoke the old token and issue a fresh one (rotation)
        rt.revoke(now);
        refreshTokens.save(rt);

        String newRefreshTokenRaw = issueRefreshToken(c.getId(), now);
        audit.record("customer:" + c.getId(), "TOKEN_REFRESHED", null, null);
        return new LoginResponse(jwt.issue(c), "Bearer", jwt.ttlSeconds(), newRefreshTokenRaw);
    }

    @Transactional
    public void logout(String refreshTokenRaw) {
        if (refreshTokenRaw != null && !refreshTokenRaw.isBlank() && refreshTokens != null) {
            String hash = hashToken(refreshTokenRaw.trim());
            refreshTokens.findByTokenHash(hash).ifPresent(rt -> {
                rt.revoke(clock.instant());
                refreshTokens.save(rt);
            });
        }
    }

    private String issueRefreshToken(Long customerId, Instant now) {
        if (refreshTokens == null) {
            return null;
        }
        String raw = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        String hash = hashToken(raw);
        RefreshToken rt = new RefreshToken(customerId, hash, now.plus(REFRESH_TOKEN_DAYS, ChronoUnit.DAYS));
        refreshTokens.save(rt);
        return raw;
    }

    private static String hashToken(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : encoded) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not supported", e);
        }
    }
}
