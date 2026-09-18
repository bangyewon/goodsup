package com.goodsup.demo.notification.service;

import com.goodsup.demo.common.AbstractConcurrencyIntegrationTest;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.notification.domain.NotificationRepository;
import com.goodsup.demo.notification.domain.NotificationType;
import com.goodsup.demo.orders.domain.OrdersRepository;
import com.goodsup.demo.orders.dto.request.ParticipateGoodsFundingRequest;
import com.goodsup.demo.orders.service.OrderService;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.domain.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0002 실험 설계 시나리오 3(마감임박 알림 잡의 중복 발송 방지)을 실행한다. ADR 작성 시점에는
 * "잡 1이 아직 구현되지 않아 이번 실험에서 실행하지 못했다 — 후속 작업으로 남긴다"고 명시됐었다.
 * 잡 1은 GoodsFunding.status를 바꾸지 않으므로, NotificationService의 existsBy 사전체크 +
 * Notification 유니크 제약(uk_notification_user_funding_type) 조합(B-1 패턴)만으로 배치가
 * 중첩 실행돼도 참여자당 정확히 한 번만 알림이 발송되는지 검증한다.
 */
class DeadlineSoonNotificationConcurrencyTest extends AbstractConcurrencyIntegrationTest {

    private static final int PARTICIPANTS = 19;
    private static final int CONCURRENT_CALLS = 10;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GoodsFundingRepository goodsFundingRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private OrdersRepository ordersRepository;
    @Autowired
    private OrderService orderService;
    @Autowired
    private NotificationService notificationService;

    @AfterEach
    void cleanUp() {
        ordersRepository.deleteAllInBatch();
        notificationRepository.deleteAllInBatch();
        goodsFundingRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private User createUser(String emailPrefix) {
        return userRepository.saveAndFlush(User.builder()
                .email(emailPrefix + "@deadline-soon-experiment.com")
                .password("password")
                .nickname(emailPrefix)
                .build());
    }

    @Test
    void 마감임박_알림_잡이_중첩_실행돼도_참여자당_정확히_한_번만_발송된다() throws InterruptedException {
        User host = createUser("host");
        GoodsFunding goodsFunding = goodsFundingRepository.saveAndFlush(GoodsFunding.builder()
                .host(host)
                .title("마감임박 알림 실험용 굿즈펀딩")
                .description("설명")
                .price(1000)
                .targetQuantity(PARTICIPANTS + 1)
                .maxQuantityPerUser(1)
                .deadlineAt(LocalDateTime.now().plusHours(1))
                .build());
        Long goodsFundingId = goodsFunding.getId();

        for (int i = 0; i < PARTICIPANTS; i++) {
            User participant = createUser("p" + i);
            orderService.participateGoodsFunding(
                    participant.getId(), goodsFundingId, new ParticipateGoodsFundingRequest(1));
        }
        List<Long> participantIds = orderService.findParticipantUserIds(goodsFundingId);

        ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_CALLS);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(CONCURRENT_CALLS);
        AtomicInteger totalSent = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_CALLS; i++) {
            executorService.submit(() -> {
                try {
                    startSignal.await();
                    totalSent.addAndGet(notificationService.notifyDeadlineSoon(goodsFundingId, participantIds));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneSignal.countDown();
                }
            });
        }

        startSignal.countDown();
        boolean completed = doneSignal.await(30, TimeUnit.SECONDS);
        executorService.shutdown();
        assertThat(completed).isTrue();

        long deadlineSoonNotificationCount = notificationRepository.countByGoodsFundingIdAndType(
                goodsFundingId, NotificationType.DEADLINE_SOON);

        assertThat(deadlineSoonNotificationCount).isEqualTo(PARTICIPANTS);
        assertThat(totalSent.get()).isEqualTo(PARTICIPANTS);
    }
}
