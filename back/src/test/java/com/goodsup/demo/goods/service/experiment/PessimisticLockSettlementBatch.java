package com.goodsup.demo.goods.service.experiment;

import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.goods.domain.GoodsFundingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ADR-0002 실험 후보 A-1의 진입점. 의도적으로 @Transactional이 아니다 —
 * 후보 id 조회는 락 없이 스냅샷으로 하고, id별 전이는 PessimisticLockSettlementExecutor가
 * 각각 짧은 트랜잭션(락 1 row)으로 처리해야 대량 처리 시 락을 오래 쥐지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PessimisticLockSettlementBatch {

    private final GoodsFundingRepository goodsFundingRepository;
    private final PessimisticLockSettlementExecutor executor;

    public int settleExpiredFundings(LocalDateTime referenceTime) {
        List<Long> candidateIds = goodsFundingRepository.findIdsByStatusAndDeadlineAtBefore(
                GoodsFundingStatus.RECRUITING, referenceTime);

        int settledCount = 0;
        for (Long candidateId : candidateIds) {
            if (executor.settleOne(candidateId, referenceTime)) {
                settledCount++;
            }
        }
        return settledCount;
    }
}
