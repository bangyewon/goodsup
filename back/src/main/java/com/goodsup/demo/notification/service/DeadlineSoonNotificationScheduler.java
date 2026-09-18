package com.goodsup.demo.notification.service;

import com.goodsup.demo.goods.service.GoodsFundingService;
import com.goodsup.demo.orders.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeadlineSoonNotificationScheduler {

    private final GoodsFundingService goodsFundingService;
    private final OrderService orderService;
    private final NotificationService notificationService;

    @Value("${goodsup.notification.deadline-soon.threshold-hours:24}")
    private long thresholdHours;

    @Scheduled(cron = "${goodsup.notification.deadline-soon.cron:0 0 * * * *}")
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.plusHours(thresholdHours);
        List<Long> candidateIds = goodsFundingService.findDeadlineSoonFundingIds(now, threshold);

        int totalSent = 0;
        for (Long fundingId : candidateIds) {
            try {
                List<Long> participantIds = orderService.findParticipantUserIds(fundingId);
                totalSent += notificationService.notifyDeadlineSoon(fundingId, participantIds);
            } catch (RuntimeException e) {
                log.warn("마감임박 알림 처리 실패: goodsFundingId={}", fundingId, e);
            }
        }
        log.info("마감임박 알림 배치 완료: 대상 공구 {}건, 발송 {}건", candidateIds.size(), totalSent);
    }
}
