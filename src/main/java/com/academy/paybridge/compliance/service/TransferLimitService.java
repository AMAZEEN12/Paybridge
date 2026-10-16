package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.compliance.api.TransferLimitApi;
import com.academy.paybridge.compliance.api.TransferLimitContext;
import org.springframework.stereotype.Service;

@Service
public class TransferLimitService implements TransferLimitApi {

    private final TransferLimitProperties props;

    public TransferLimitService(TransferLimitProperties props) {
        this.props = props;
    }

    @Override
    public FraudResult evaluate(TransferLimitContext context) {
        return TransferLimitRules.evaluate(props, context);
    }
}