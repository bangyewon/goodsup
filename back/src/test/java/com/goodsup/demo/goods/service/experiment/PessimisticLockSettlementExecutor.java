package com.goodsup.demo.goods.service.experiment;

import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * ADR-0002 실험 후보 A-1: id 1건 = 트랜잭션 1개. 참여 경로(OrderService)와 동일한
 * findByIdForUpdate 비관적 락을 재사용해 정산 판정과 참여 요청의 경합을 막는다.
 * PessimisticLockSettlementBatch가 이 메서드를 외부에서 호출해야 @Transactional AOP가
 * 적용된다(self-invocation을 피하기 위해 별도 빈으로 분리).
 */
@Service
@RequiredArgsConstructor
public class PessimisticLockSettlementExecutor {

    private final GoodsFundingRepository goodsFundingRepository;
    private final SettlementNotifier settlementNotifier;

    @Transactional
    public boolean settleOne(Long goodsFundingId, LocalDateTime referenceTime) {
        GoodsFunding goodsFunding = goodsFundingRepository.findByIdForUpdate(goodsFundingId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));

        boolean transitioned = goodsFunding.closeAsFailedIfDeadlinePassed(referenceTime);
        if (transitioned) {
            settlementNotifier.notifyFundingFailed(goodsFunding);
        }
        return transitioned;
    }
}
