package com.goodsup.demo.goods.service;

import com.goodsup.demo.notification.service.NotificationService;
import com.goodsup.demo.orders.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeadlineSettlementScheduler {

    private final GoodsFundingService goodsFundingService;
    private final OrderService orderService;
    private final NotificationService notificationService;

    @Scheduled(cron = "${goodsup.settlement.deadline.cron:0 0 * * * *}")
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> candidateIds = goodsFundingService.findExpiredRecruitingIds(now);

        int settledCount = 0;
        for (Long fundingId : candidateIds) {
            try {
                if (goodsFundingService.settleAsFailed(fundingId, now)) {
                    List<Long> participantIds = orderService.findParticipantUserIds(fundingId);
                    notificationService.notifyFundingFailed(fundingId, participantIds);
                    settledCount++;
                }
            } catch (DataAccessException e) {
                log.warn("마감 정산 처리 실패(락 경합/DB 오류): goodsFundingId={}", fundingId, e);
            }
        }
        log.info("마감 정산 배치 완료: 대상 공구 {}건, FAILED 전이 {}건", candidateIds.size(), settledCount);
    }
}
