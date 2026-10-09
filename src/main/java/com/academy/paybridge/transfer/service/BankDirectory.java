package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.gateway.Bank;
import com.academy.paybridge.transfer.gateway.TransferGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The list of banks, loaded from the gateway and cached. A customer picks a bank from this list;
 * nobody types a bank code by hand. If a refresh fails we keep the old list.
 */
@Service
public class BankDirectory {

    private static final Logger log = LoggerFactory.getLogger(BankDirectory.class);

    private final TransferGateway gateway;
    private volatile List<Bank> cache = List.of();

    public BankDirectory(TransferGateway gateway) {
        this.gateway = gateway;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadAtStartup() {
        refresh();
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT6H")
    public void refresh() {
        try {
            List<Bank> fresh = gateway.listBanks().stream()
                    .sorted(Comparator.comparing(Bank::name, String.CASE_INSENSITIVE_ORDER)).toList();
            if (!fresh.isEmpty()) {
                cache = fresh;
                log.info("Loaded {} banks", fresh.size());
            }
        } catch (RuntimeException e) {
            log.warn("Could not refresh the bank list: {}", e.getClass().getSimpleName());
        }
    }

    public List<Bank> all() {
        if (cache.isEmpty()) {
            refresh();
        }
        return cache;
    }

    public Optional<Bank> find(String code) {
        return all().stream().filter(b -> b.code().equals(code)).findFirst();
    }
}
