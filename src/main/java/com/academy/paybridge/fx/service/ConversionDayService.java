package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.ConversionDayApi;
import com.academy.paybridge.fx.repository.QuoteRepository;
import com.academy.paybridge.shared.api.DayItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ConversionDayService implements ConversionDayApi {

    private final QuoteRepository quotes;

    public ConversionDayService(QuoteRepository quotes) {
        this.quotes = quotes;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayItem> conversionsBetween(Instant from, Instant to) {
        return quotes.conversionDayItems(from, to);
    }
}