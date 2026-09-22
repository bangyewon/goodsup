package com.goodsup.demo.goods.service;

import com.goodsup.demo.common.AbstractConcurrencyIntegrationTest;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.goods.domain.GoodsFundingStatus;
import com.goodsup.demo.notification.domain.NotificationRepository;
import com.goodsup.demo.notification.domain.NotificationType;
import com.goodsup.demo.notification.service.NotificationService;
import com.goodsup.demo.orders.domain.OrdersRepository;
import com.goodsup.demo.orders.dto.request.ParticipateGoodsFundingRequest;
import com.goodsup.demo.orders.service.OrderService;
import com.goodsup.demo.payment.domain.OutboxEventRepository;
import com.goodsup.demo.payment.domain.PaymentRepository;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.domain.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * ADR-0002(A-1 + B-1)를 실제 배선한 프로덕션 경로({@link GoodsFundingService#settleAsFailed} ->
 * {@link NotificationService#notifyFundingFailed})에 대해, 실험(goods.service.experiment 패키지)에서
 * 검증했던 시나리오 1·2를 재현한다.
 *
 * {@link DeadlineSettlementScheduler#run()}을 직접 동시 호출하지 않고 {@link #settleAndNotify}로
 * referenceTime을 주입해 "settle 한 건 + 알림"만 재현하는 이유: 스케줄러의 run()은 후보 조회와
 * 판정 모두 내부에서 LocalDateTime.now()를 직접 호출해 결정론적 재현이 불가능하다(ADR-0002가 이미
 * 겪은 벽시계 기반 버퍼 문제와 동일한 함정). 스케줄러 자체의 항목별 예외 격리 동작은
 * {@link DeadlineSettlementSchedulerTest}에서 Mockito로 별도 검증한다.
 */
class DeadlineSettlementConcurrencyTest extends AbstractConcurrencyIntegrationTest {

    private static final int TARGET_QUANTITY = 20;
    private static final int PRE_PARTICIPANTS = TARGET_QUANTITY - 1;
    private static final int RACE_PARTICIPANTS = 50;
    private static final int CONCURRENT_BATCH_CALLS = 10;

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
    private GoodsFundingService goodsFundingService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        ordersRepository.deleteAllInBatch();
        notificationRepository.deleteAllInBatch();
        goodsFundingRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private User createUser(String emailPrefix) {
        return userRepository.saveAndFlush(User.builder()
                .email(emailPrefix + "@settlement-prod.com")
                .password("password")
                .nickname(emailPrefix)
                .build());
    }

    private GoodsFunding createFunding(User host, LocalDateTime deadlineAt) {
        return goodsFundingRepository.saveAndFlush(GoodsFunding.builder()
                .host(host)
                .title("정산 프로덕션 경로 테스트용 굿즈펀딩")
                .description("설명")
                .price(1000)
                .targetQuantity(TARGET_QUANTITY)
                .maxQuantityPerUser(1)
                .deadlineAt(deadlineAt)
                .build());
    }

    private void preParticipate(Long goodsFundingId, int count, String prefix) {
        for (int i = 0; i < count; i++) {
            User participant = createUser(prefix + i);
            orderService.participateGoodsFunding(
                    participant.getId(), goodsFundingId, new ParticipateGoodsFundingRequest(1));
        }
    }

    /** DeadlineSettlementScheduler.run()의 항목 1건 처리부와 동일한 순서(settle -> notify). */
    private int settleAndNotify(Long goodsFundingId, LocalDateTime referenceTime) {
        if (!goodsFundingService.settleAsFailed(goodsFundingId, referenceTime)) {
            return 0;
        }
        List<Long> participantIds = orderService.findParticipantUserIds(goodsFundingId);
        notificationService.notifyFundingFailed(goodsFundingId, participantIds);
        return 1;
    }

    @Test
    void 시나리오1_마지막_한자리를_두고_참여와_정산이_경합해도_둘_중_하나만_확정된다() throws InterruptedException {
        User host = createUser("host1");
        LocalDateTime deadlineAt = LocalDateTime.now().plusSeconds(10);
        GoodsFunding goodsFunding = createFunding(host, deadlineAt);
        Long goodsFundingId = goodsFunding.getId();

        preParticipate(goodsFundingId, PRE_PARTICIPANTS, "pre1-");

        List<Long> raceParticipantIds = new ArrayList<>();
        for (int i = 0; i < RACE_PARTICIPANTS; i++) {
            raceParticipantIds.add(createUser("race1-" + i).getId());
        }

        LocalDateTime referenceTimeAfterDeadline = deadlineAt.plusNanos(1);

        ExecutorService executorService = Executors.newFixedThreadPool(30);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(RACE_PARTICIPANTS + CONCURRENT_BATCH_CALLS);
        AtomicInteger participationSuccessCount = new AtomicInteger();
        AtomicInteger settleCount = new AtomicInteger();

        for (Long participantId : raceParticipantIds) {
            executorService.submit(() -> {
                try {
                    startSignal.await();
                    try {
                        orderService.participateGoodsFunding(
                                participantId, goodsFundingId, new ParticipateGoodsFundingRequest(1));
                        participationSuccessCount.incrementAndGet();
                    } catch (Exception ignored) {
                        // 마감/재고 소진에 따른 정상적인 거절은 무시한다.
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneSignal.countDown();
                }
            });
        }
        for (int i = 0; i < CONCURRENT_BATCH_CALLS; i++) {
            executorService.submit(() -> {
                try {
                    startSignal.await();
                    settleCount.addAndGet(settleAndNotify(goodsFundingId, referenceTimeAfterDeadline));
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

        GoodsFunding reloaded = goodsFundingRepository.findById(goodsFundingId).orElseThrow();
        long failedNotificationCount = notificationRepository.countByGoodsFundingIdAndType(
                goodsFundingId, NotificationType.FUNDING_FAILED);

        if (reloaded.getStatus() == GoodsFundingStatus.FINISHED) {
            assertThat(reloaded.getCurrentQuantity()).isEqualTo(TARGET_QUANTITY);
            assertThat(participationSuccessCount.get()).isEqualTo(1);
            assertThat(settleCount.get()).isEqualTo(0);
            assertThat(failedNotificationCount).isEqualTo(0);
        } else if (reloaded.getStatus() == GoodsFundingStatus.FAILED) {
            assertThat(reloaded.getCurrentQuantity()).isEqualTo(TARGET_QUANTITY - 1);
            assertThat(participationSuccessCount.get()).isEqualTo(0);
            assertThat(settleCount.get()).isEqualTo(1);
            assertThat(failedNotificationCount).isEqualTo(PRE_PARTICIPANTS);
        } else {
            fail("정산 결과는 FINISHED/FAILED 둘 중 하나여야 하는데 실제 상태: " + reloaded.getStatus());
        }
    }

    @Test
    void 시나리오2_정산이_중첩_실행돼도_전이와_알림은_참여자당_정확히_한_번만_일어난다() throws InterruptedException {
        User host = createUser("host2");
        LocalDateTime deadlineAt = LocalDateTime.now().plusSeconds(10);
        GoodsFunding goodsFunding = createFunding(host, deadlineAt);
        Long goodsFundingId = goodsFunding.getId();

        preParticipate(goodsFundingId, PRE_PARTICIPANTS, "pre2-");

        LocalDateTime referenceTimeAfterDeadline = deadlineAt.plusSeconds(1);

        ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_BATCH_CALLS);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(CONCURRENT_BATCH_CALLS);
        AtomicInteger settleCount = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_BATCH_CALLS; i++) {
            executorService.submit(() -> {
                try {
                    startSignal.await();
                    settleCount.addAndGet(settleAndNotify(goodsFundingId, referenceTimeAfterDeadline));
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

        GoodsFunding reloaded = goodsFundingRepository.findById(goodsFundingId).orElseThrow();
        long failedNotificationCount = notificationRepository.countByGoodsFundingIdAndType(
                goodsFundingId, NotificationType.FUNDING_FAILED);

        assertThat(reloaded.getStatus()).isEqualTo(GoodsFundingStatus.FAILED);
        assertThat(settleCount.get()).isEqualTo(1);
        assertThat(failedNotificationCount).isEqualTo(PRE_PARTICIPANTS);
    }
}
