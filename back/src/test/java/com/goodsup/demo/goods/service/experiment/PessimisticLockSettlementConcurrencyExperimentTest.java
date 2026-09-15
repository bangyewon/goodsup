package com.goodsup.demo.goods.service.experiment;

import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

/**
 * ADR-0002 후보 A-1(단건 비관적 락 재사용, {@link PessimisticLockSettlementBatch}) 실측용.
 */
class PessimisticLockSettlementConcurrencyExperimentTest extends AbstractSettlementConcurrencyExperimentTest {

    @Autowired
    private PessimisticLockSettlementBatch pessimisticLockSettlementBatch;

    @Override
    protected int settle(LocalDateTime referenceTime) {
        return pessimisticLockSettlementBatch.settleExpiredFundings(referenceTime);
    }
}
