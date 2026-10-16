package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.CorridorApi;
import com.academy.paybridge.compliance.api.CorridorResult;
import org.springframework.stereotype.Service;

@Service
public class CorridorService implements CorridorApi {

    private final CorridorProperties props;

    public CorridorService(CorridorProperties props) {
        this.props = props;
    }

    @Override
    public CorridorResult check(String senderCountry, String destinationCountry) {
        return CorridorRules.evaluate(props.rules(), senderCountry, destinationCountry);
    }
}