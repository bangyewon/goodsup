package com.goodsup.demo.goods.service.experiment;

import com.goodsup.demo.common.AbstractConcurrencyIntegrationTest;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.goods.domain.GoodsFundingStatus;
import com.goodsup.demo.notification.domain.NotificationRepository;
import com.goodsup.demo.notification.domain.NotificationType;
import com.goodsup.demo.orders.domain.OrdersRepository;
import com.goodsup.demo.orders.dto.request.ParticipateGoodsFundingRequest;
import com.goodsup.demo.payment.domain.PaymentMethod;
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
 * ADR-0002 실험 설계 시나리오 1·2를 정산 후보(A-1/A-2) 구현체별로 동일하게 재현하는 공통 베이스.
 * 하위 클래스는 settle(referenceTime)만 자신의 후보 서비스로 위임하면 된다 — 그래야 두 후보가
 * "정확히 같은 조건"에서 비교된다.
 */
abstract class AbstractSettlementConcurrencyExperimentTest extends AbstractConcurrencyIntegrationTest {

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
    private PaymentRepository paymentRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    /**
     * 정산 실험 테스트는 싱글턴 Testcontainers MySQL을 여러 테스트 클래스(A-1/A-2 후보)가
     * 공유한다({@link com.goodsup.demo.common.AbstractConcurrencyIntegrationTest} 참고).
     * 스레드 간 동시 호출을 재현해야 해서 각 테스트를 하나의 트랜잭션으로 감싸 롤백할 수 없으므로,
     * 매 테스트 종료 후 FK 순서(payment -> outbox_event -> orders -> notification -> goods_funding -> user)로
     * 직접 정리한다(payment가 orders를 FK로 참조하므로 orders보다 먼저 지워야 한다).
     */
    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        ordersRepository.deleteAllInBatch();
        notificationRepository.deleteAllInBatch();
        goodsFundingRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    /**
     * 하위 클래스가 실험 대상 후보(A-1 또는 A-2)의 정산 메서드를 호출하도록 구현.
     * 반환값은 "이번 호출로 실제 FAILED 전이가 일어난 건수"(0 또는 1, 이 테스트는 GoodsFunding 1건만 다룸).
     */
    protected abstract int settle(LocalDateTime referenceTime);

    private User createUser(String emailPrefix) {
        return userRepository.saveAndFlush(User.builder()
                .email(emailPrefix + "@settlement-experiment.com")
                .password("password")
                .nickname(emailPrefix)
                .build());
    }

    private GoodsFunding createFunding(User host, LocalDateTime deadlineAt) {
        return goodsFundingRepository.saveAndFlush(GoodsFunding.builder()
                .host(host)
                .title("정산 실험용 굿즈펀딩")
                .description("설명")
                .price(1000)
                .targetQuantity(TARGET_QUANTITY)
                .maxQuantityPerUser(1)
                .deadlineAt(deadlineAt)
                .build());
    }

    /** 레이스 시작 전 사전 설정 단계에서 순차적으로 count명을 참여시킨다(각 1개씩). */
    private void preParticipate(Long goodsFundingId, int count, String prefix) {
        for (int i = 0; i < count; i++) {
            User participant = createUser(prefix + i);
            orderService.participateGoodsFunding(
                    participant.getId(), goodsFundingId, new ParticipateGoodsFundingRequest(1, PaymentMethod.CARD));
        }
    }

    @Test
    void 시나리오1_마지막_한자리를_두고_참여와_배치가_경합하면_둘_중_하나만_확정된다() throws InterruptedException {
        User host = createUser("host1");
        // 사전 설정 단계(순차 참여 19회 + 레이스 참가자 50명 생성)가 실제 벽시계 기준으로
        // 마감 전에 끝나야 하므로 넉넉한 버퍼를 둔다(레이스 자체는 CountDownLatch로 동시
        // 출발하므로 버퍼 크기와 무관하게 타이밍이 결정된다).
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
        AtomicInteger batchTransitionCount = new AtomicInteger();

        for (Long participantId : raceParticipantIds) {
            executorService.submit(() -> {
                try {
                    startSignal.await();
                    try {
                        orderService.participateGoodsFunding(
                                participantId, goodsFundingId, new ParticipateGoodsFundingRequest(1, PaymentMethod.CARD));
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
                    batchTransitionCount.addAndGet(settle(referenceTimeAfterDeadline));
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
            assertThat(batchTransitionCount.get()).isEqualTo(0);
            assertThat(failedNotificationCount).isEqualTo(0);
        } else if (reloaded.getStatus() == GoodsFundingStatus.FAILED) {
            assertThat(reloaded.getCurrentQuantity()).isEqualTo(TARGET_QUANTITY - 1);
            assertThat(participationSuccessCount.get()).isEqualTo(0);
            assertThat(batchTransitionCount.get()).isEqualTo(1);
            assertThat(failedNotificationCount).isEqualTo(PRE_PARTICIPANTS);
        } else {
            fail("정산 결과는 FINISHED/FAILED 둘 중 하나여야 하는데 실제 상태: " + reloaded.getStatus());
        }
    }

    @Test
    void 시나리오2_배치가_중첩_실행돼도_전이와_알림은_정확히_한_번만_일어난다() throws InterruptedException {
        User host = createUser("host2");
        // preParticipate가 순차 호출 19회(유저 생성 + 참여)를 실제 벽시계 기준으로 마감 전에
        // 끝내야 하므로, 배치 판정 자체는 referenceTime 주입으로 결정론적이어도 이 사전 단계는
        // 넉넉한 버퍼가 필요하다(1초는 Testcontainers 환경에서 간헐적으로 부족해 재현됨).
        LocalDateTime deadlineAt = LocalDateTime.now().plusSeconds(10);
        GoodsFunding goodsFunding = createFunding(host, deadlineAt);
        Long goodsFundingId = goodsFunding.getId();

        preParticipate(goodsFundingId, PRE_PARTICIPANTS, "pre2-");

        LocalDateTime referenceTimeAfterDeadline = deadlineAt.plusSeconds(1);

        ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_BATCH_CALLS);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(CONCURRENT_BATCH_CALLS);
        AtomicInteger batchTransitionCount = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_BATCH_CALLS; i++) {
            executorService.submit(() -> {
                try {
                    startSignal.await();
                    batchTransitionCount.addAndGet(settle(referenceTimeAfterDeadline));
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
        assertThat(batchTransitionCount.get()).isEqualTo(1);
        assertThat(failedNotificationCount).isEqualTo(PRE_PARTICIPANTS);
    }
}
