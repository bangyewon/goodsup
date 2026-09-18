package com.goodsup.demo.goods.service.experiment;

import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

/**
 * ADR-0002 후보 A-2(조건부 벌크 UPDATE, {@link BulkUpdateSettlementService}) 실측용.
 */
class BulkUpdateSettlementConcurrencyExperimentTest extends AbstractSettlementConcurrencyExperimentTest {

    @Autowired
    private BulkUpdateSettlementService bulkUpdateSettlementService;

    @Override
    protected int settle(LocalDateTime referenceTime) {
        return bulkUpdateSettlementService.settleExpiredFundings(referenceTime);
    }
}
