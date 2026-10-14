package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.ConversionComplianceApi;
import com.academy.paybridge.compliance.api.ConversionContext;
import com.academy.paybridge.compliance.api.FraudResult;
import org.springframework.stereotype.Service;

@Service
public class ConversionComplianceService implements ConversionComplianceApi {

    private final ConversionLimitProperties props;

    public ConversionComplianceService(ConversionLimitProperties props) {
        this.props = props;
    }

    @Override
    public FraudResult evaluate(ConversionContext context) {
        return ConversionRules.evaluate(props, context);
    }

    @Override
    public int velocityWindowMinutes() {
        return props.velocityWindowMinutes();
    }
}
