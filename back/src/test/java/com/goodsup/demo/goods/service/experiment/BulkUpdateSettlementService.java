package com.goodsup.demo.goods.service.experiment;

import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.goods.domain.GoodsFundingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ADR-0002 실험 후보 A-2: 후보 id 조회(락 없음) -> 조건부 벌크 UPDATE(원자적) ->
 * 실제로 전이된 id만 재조회해서 알림 생성. MySQL은 UPDATE ... RETURNING을 지원하지 않아
 * "몇 건이 바뀌었는지"가 아니라 "어떤 id가 바뀌었는지"를 알려면 재조회가 필요하다는 게
 * 이 후보의 핵심 약점(ADR-0002 트레이드오프 참고).
 */
@Service
@RequiredArgsConstructor
public class BulkUpdateSettlementService {

    private final GoodsFundingRepository goodsFundingRepository;
    private final SettlementNotifier settlementNotifier;

    @Transactional
    public int settleExpiredFundings(LocalDateTime referenceTime) {
        List<Long> candidateIds = goodsFundingRepository.findIdsByStatusAndDeadlineAtBefore(
                GoodsFundingStatus.RECRUITING, referenceTime);
        if (candidateIds.isEmpty()) {
            return 0;
        }

        int updatedCount = goodsFundingRepository.bulkCloseAsFailed(candidateIds, referenceTime);
        if (updatedCount == 0) {
            return 0;
        }

        List<GoodsFunding> actuallyFailed = goodsFundingRepository.findAllByIdInAndStatus(
                candidateIds, GoodsFundingStatus.FAILED);
        for (GoodsFunding goodsFunding : actuallyFailed) {
            settlementNotifier.notifyFundingFailed(goodsFunding);
        }
        return updatedCount;
    }
}
