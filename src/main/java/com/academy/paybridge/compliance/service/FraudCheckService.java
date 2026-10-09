package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudApi;
import com.academy.paybridge.compliance.api.FraudContext;
import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.compliance.domain.FraudFlag;
import com.academy.paybridge.compliance.repository.BlockedDestinationRepository;
import com.academy.paybridge.compliance.repository.FraudFlagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FraudCheckService implements FraudApi {

    private final FraudProperties props;
    private final BlockedDestinationRepository blocked;
    private final FraudFlagRepository flags;

    public FraudCheckService(FraudProperties props, BlockedDestinationRepository blocked, FraudFlagRepository flags) {
        this.props = props;
        this.blocked = blocked;
        this.flags = flags;
    }

    @Override
    @Transactional(readOnly = true)
    public FraudResult evaluate(FraudContext context) {
        boolean destinationBlocked = blocked.existsByDestinationKey(context.destinationKey());
        return FraudRules.evaluate(props, context, destinationBlocked);
    }

    @Override
    public int velocityWindowMinutes() {
        return props.velocityWindowMinutes();
    }

    @Override
    public int passThroughWindowMinutes() {
        return props.passThroughWindowMinutes();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String reference, String accountNumber, FraudResult result) {
        if (result.decision() == Decision.ALLOW) {
            return;
        }
        flags.save(new FraudFlag(reference, accountNumber, result.decision(), result.joinedReasons()));
    }
}
