package com.goodsup.demo.payment.service;

import com.goodsup.demo.common.AbstractConcurrencyIntegrationTest;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.orders.domain.OrdersRepository;
import com.goodsup.demo.orders.dto.request.ParticipateGoodsFundingRequest;
import com.goodsup.demo.payment.domain.PaymentMethod;
import com.goodsup.demo.orders.service.OrderService;
import com.goodsup.demo.payment.domain.OutboxEvent;
import com.goodsup.demo.payment.domain.OutboxEventRepository;
import com.goodsup.demo.payment.domain.OutboxEventStatus;
import com.goodsup.demo.payment.domain.OutboxEventType;
import com.goodsup.demo.payment.domain.Payment;
import com.goodsup.demo.payment.domain.PaymentRepository;
import com.goodsup.demo.payment.domain.PaymentStatus;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.domain.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0004(A안: Outbox + 폴링 릴레이) 실측 테스트.
 * CLAUDE.md "AI를 검증 파트너로 활용" 절차에 따라 사람이 선정한 시나리오(A1~A5)를
 * {@link PaymentFanOutRelayScheduler}/{@link OutboxEventService}/{@link PaymentService}의
 * 실제 프로덕션 경로({@link OrderService#participateGoodsFunding})로 재현한다.
 */
@Slf4j
@Import(PaymentFanOutRelayConcurrencyTest.FakeGatewayConfig.class)
class PaymentFanOutRelayConcurrencyTest extends AbstractConcurrencyIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GoodsFundingRepository goodsFundingRepository;
    @Autowired
    private OrdersRepository ordersRepository;
    @Autowired
    private OrderService orderService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private PaymentFanOutRelayScheduler paymentFanOutRelayScheduler;
    @Autowired
    private FakePgPaymentGateway fakePgPaymentGateway;

    @TestConfiguration
    static class FakeGatewayConfig {
        @Bean
        @Primary
        FakePgPaymentGateway fakePgPaymentGateway() {
            return new FakePgPaymentGateway();
        }
    }

    /**
     * 정산 실험과 동일하게(AbstractSettlementConcurrencyExperimentTest 참고) 싱글턴 컨테이너를
     * 공유하므로, 매 테스트 종료 후 FK 순서(payment -> outbox_event -> orders -> goods_funding -> user)로
     * 직접 정리한다.
     */
    @AfterEach
    void cleanUp() {
        fakePgPaymentGateway.reset();
        paymentRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        ordersRepository.deleteAllInBatch();
        goodsFundingRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private User createUser(String emailPrefix) {
        return userRepository.saveAndFlush(User.builder()
                .email(emailPrefix + "@fanout-experiment.com")
                .password("password")
                .nickname(emailPrefix)
                .build());
    }

    private GoodsFunding createFunding(User host, int targetQuantity, int maxQuantityPerUser) {
        return goodsFundingRepository.saveAndFlush(GoodsFunding.builder()
                .host(host)
                .title("fan-out 실험용 굿즈펀딩")
                .description("설명")
                .price(1000)
                .targetQuantity(targetQuantity)
                .maxQuantityPerUser(maxQuantityPerUser)
                .deadlineAt(LocalDateTime.now().plusMinutes(10))
                .build());
    }

    private void preParticipate(Long goodsFundingId, int count, String prefix) {
        for (int i = 0; i < count; i++) {
            User participant = createUser(prefix + i);
            orderService.participateGoodsFunding(
                    participant.getId(), goodsFundingId, new ParticipateGoodsFundingRequest(1, PaymentMethod.CARD));
        }
    }

    private OutboxEvent fanOutEventOf(Long goodsFundingId) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(goodsFundingId)
                        && e.getEventType() == OutboxEventType.PAYMENT_FANOUT_REQUESTED)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void A1_참여_레이스로_마지막_한자리를_두고_경합해도_결제_fanout_이벤트는_정확히_한_건만_생성된다() throws InterruptedException {
        int targetQuantity = 20;
        int raceParticipants = 50;

        User host = createUser("fanout-host1");
        GoodsFunding funding = createFunding(host, targetQuantity, 1);
        Long fundingId = funding.getId();

        preParticipate(fundingId, targetQuantity - 1, "fanout-pre1-");

        List<Long> raceUserIds = new ArrayList<>();
        for (int i = 0; i < raceParticipants; i++) {
            raceUserIds.add(createUser("fanout-race1-" + i).getId());
        }

        ExecutorService executorService = Executors.newFixedThreadPool(20);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(raceParticipants);
        for (Long userId : raceUserIds) {
            executorService.submit(() -> {
                try {
                    startSignal.await();
                    try {
                        orderService.participateGoodsFunding(
                                userId, fundingId, new ParticipateGoodsFundingRequest(1, PaymentMethod.CARD));
                    } catch (Exception ignored) {
                        // 재고 소진/마감에 따른 정상적인 거절은 무시한다.
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneSignal.countDown();
                }
            });
        }
        startSignal.countDown();
        assertThat(doneSignal.await(30, TimeUnit.SECONDS)).isTrue();
        executorService.shutdown();

        List<OutboxEvent> fanOutEvents = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(fundingId)
                        && e.getEventType() == OutboxEventType.PAYMENT_FANOUT_REQUESTED)
                .toList();
        List<Long> requestedPaymentIds = paymentService.findRequestedPaymentIdsByGoodsFundingId(fundingId);

        assertThat(fanOutEvents).hasSize(1);
        assertThat(requestedPaymentIds).hasSize(targetQuantity);
    }

    @Test
    void A2_lease_초과로_재claim되면_같은_결제가_PG에_중복_호출될_수_있다() throws Exception {
        User host = createUser("fanout-host2");
        GoodsFunding funding = createFunding(host, 2, 1);
        Long fundingId = funding.getId();

        Long orderId1 = orderService.participateGoodsFunding(
                createUser("fanout-p2-1").getId(), fundingId, new ParticipateGoodsFundingRequest(1, PaymentMethod.CARD)).id();
        Long orderId2 = orderService.participateGoodsFunding(
                createUser("fanout-p2-2").getId(), fundingId, new ParticipateGoodsFundingRequest(1, PaymentMethod.CARD)).id();

        LocalDateTime claimTime = LocalDateTime.now();
        LocalDateTime farPast = claimTime.minusMinutes(10);
        // 실제로 5분(LEASE_TIMEOUT)을 기다리는 대신, staleBefore를 claimTime 이후로 주입해
        // "lease가 만료됐다"는 상황을 결정론적으로 재현한다(runOnce의 staleBefore는 호출자가
        // 완전히 통제하는 파라미터이므로 벽시계 경과 없이도 동일한 분기를 탄다).
        LocalDateTime staleBeforeSimulatingExpiredLease = claimTime.plusSeconds(1);

        CountDownLatch sharedRelease = new CountDownLatch(1);
        CountDownLatch worker1Started = new CountDownLatch(1);
        fakePgPaymentGateway.blockOrderUntil(orderId1, sharedRelease, worker1Started);

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        // worker-1을 먼저 백그라운드 스레드에서 claim시키고, order1 결제 호출 도중 블록에
        // 들어갈 때까지 기다린 뒤에야 worker-2를 출발시킨다 — 그래야 worker-2가 "아직 아무도
        // claim 안 한 PENDING row"가 아니라 "lease 만료로 재claim 가능한 PROCESSING row"를
        // 보게 된다(둘을 동시에 출발시키면 애초에 다른 시나리오인 최초 claim 경합을 재현하게 된다).
        Future<Integer> worker1 = executorService.submit(
                () -> paymentFanOutRelayScheduler.runOnce(claimTime, farPast, "worker-1"));
        assertThat(worker1Started.await(10, TimeUnit.SECONDS)).isTrue();

        // blockReleaseLatch(sharedRelease)는 그대로 두고, "블록 시작 신호"만 worker-2용으로 교체한다.
        CountDownLatch worker2Started = new CountDownLatch(1);
        fakePgPaymentGateway.blockOrderUntil(orderId1, sharedRelease, worker2Started);
        Future<Integer> worker2 = executorService.submit(() -> paymentFanOutRelayScheduler.runOnce(
                claimTime.plusSeconds(2), staleBeforeSimulatingExpiredLease, "worker-2"));
        // worker-1과 worker-2가 order1 결제 호출 안에서 동시에 블록된 것을 확인한 뒤에야 푼다 —
        // 이 시점에 도달한다는 것 자체가 "두 워커가 실제로 동시에 같은 결제를 PG에 호출하려는
        // 순간"이 재현됐다는 뜻이다.
        assertThat(worker2Started.await(10, TimeUnit.SECONDS)).isTrue();
        sharedRelease.countDown();

        Integer worker1Processed = null;
        Throwable worker1Failure = null;
        try {
            worker1Processed = worker1.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            worker1Failure = e.getCause();
        }
        Integer worker2Processed = null;
        Throwable worker2Failure = null;
        try {
            worker2Processed = worker2.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            worker2Failure = e.getCause();
        }
        executorService.shutdown();

        log.info("A2 결과: worker1Processed={}, worker1Failure={}, worker2Processed={}, worker2Failure={}, order1 PG 호출 횟수={}",
                worker1Processed, worker1Failure, worker2Processed, worker2Failure,
                fakePgPaymentGateway.callCountFor(orderId1));

        // 두 워커가 동시에 order1을 결제 승인 호출했는지 확인한다 — DB 상태는 정합하더라도
        // 외부 PG 호출 자체는 lease 재claim 윈도우에서 중복될 수 있다는 가설을 검증한다.
        assertThat(fakePgPaymentGateway.callCountFor(orderId1)).isEqualTo(2);

        // 회귀 고정: 뒤늦게 완료 처리를 시도하는 워커가 이미 PROCESSED된 row를 만나도
        // OutboxEvent.markProcessed()가 예외 없이 흡수해야 한다(최초 재현 시엔 worker1Failure가
        // IllegalStateException이었음 — ADR-0004 실측 로그 참고).
        assertThat(worker1Failure).isNull();
        assertThat(worker2Failure).isNull();
        assertThat(worker1Processed).isEqualTo(1);
        assertThat(worker2Processed).isEqualTo(1);

        Payment payment1 = paymentRepository.findByOrderId(orderId1).orElseThrow();
        Payment payment2 = paymentRepository.findByOrderId(orderId2).orElseThrow();
        assertThat(payment1.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment2.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void A3_A4_이미_성공한_결제는_재시도에서_다시_호출되지_않고_소진분만_FAILED로_확정된다() {
        User host = createUser("fanout-host3");
        GoodsFunding funding = createFunding(host, 2, 1);
        Long fundingId = funding.getId();

        Long orderIdSucceeds = orderService.participateGoodsFunding(
                createUser("fanout-p3-1").getId(), fundingId, new ParticipateGoodsFundingRequest(1, PaymentMethod.CARD)).id();
        Long orderIdAlwaysFails = orderService.participateGoodsFunding(
                createUser("fanout-p3-2").getId(), fundingId, new ParticipateGoodsFundingRequest(1, PaymentMethod.CARD)).id();
        fakePgPaymentGateway.alwaysFail(orderIdAlwaysFails);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime farPast = now.minusMinutes(10);

        // 1차 시도: 성공 건은 SUCCEEDED로 종결되고, 실패 건만 재시도 대기(attemptCount=1)로 남는다.
        int firstAttemptProcessed = paymentFanOutRelayScheduler.runOnce(now, farPast, "worker-a");
        assertThat(firstAttemptProcessed).isZero(); // 부분 실패라 이번 outbox row는 아직 완결되지 않음
        assertThat(paymentRepository.findByOrderId(orderIdSucceeds).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentRepository.findByOrderId(orderIdAlwaysFails).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REQUESTED);
        assertThat(fakePgPaymentGateway.callCountFor(orderIdSucceeds)).isEqualTo(1);

        // PaymentFanOutRelayScheduler의 지수 백오프 공식을 그대로 반영해, 실제 대기 없이
        // "다음 재시도 시각"으로 가상 시계를 이동시키며 남은 재시도(최대 MAX_ATTEMPTS)를 모두 소진시킨다.
        LocalDateTime virtualNow = now;
        for (int attemptCountAfterFailure = 1; attemptCountAfterFailure < PaymentFanOutRelayScheduler.MAX_ATTEMPTS;
             attemptCountAfterFailure++) {
            long delaySeconds = Math.min(
                    PaymentFanOutRelayScheduler.BASE_BACKOFF.toSeconds() * (1L << attemptCountAfterFailure),
                    PaymentFanOutRelayScheduler.MAX_BACKOFF.toSeconds());
            virtualNow = virtualNow.plusSeconds(delaySeconds + 1);
            paymentFanOutRelayScheduler.runOnce(virtualNow, farPast, "worker-a");
        }

        log.info("A3/A4 결과: 성공건 PG 호출={}회, 실패건 PG 호출={}회(MAX_ATTEMPTS={})",
                fakePgPaymentGateway.callCountFor(orderIdSucceeds),
                fakePgPaymentGateway.callCountFor(orderIdAlwaysFails),
                PaymentFanOutRelayScheduler.MAX_ATTEMPTS);

        // A3: 이미 성공한 결제는 이후 재시도 사이클에서 다시 호출되지 않는다.
        assertThat(fakePgPaymentGateway.callCountFor(orderIdSucceeds)).isEqualTo(1);
        assertThat(fakePgPaymentGateway.callCountFor(orderIdAlwaysFails))
                .isEqualTo(PaymentFanOutRelayScheduler.MAX_ATTEMPTS);

        // A4: 재시도를 모두 소진해도 이미 성공한 결제는 보존되고, 실패 건만 FAILED로 강제 확정된다.
        assertThat(paymentRepository.findByOrderId(orderIdSucceeds).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentRepository.findByOrderId(orderIdAlwaysFails).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(fanOutEventOf(fundingId).getStatus()).isEqualTo(OutboxEventStatus.FAILED);
    }

    @Test
    void B1_PENDING_결제는_재시도_예산을_소모하지_않고_재확인만_예약되다가_입금_확인되면_정상_종결된다() {
        User host = createUser("fanout-host-b1");
        GoodsFunding funding = createFunding(host, 2, 1);
        Long fundingId = funding.getId();

        Long orderIdSucceeds = orderService.participateGoodsFunding(
                createUser("fanout-pb1-1").getId(), fundingId, new ParticipateGoodsFundingRequest(1, PaymentMethod.CARD)).id();
        Long orderIdPending = orderService.participateGoodsFunding(
                createUser("fanout-pb1-2").getId(), fundingId,
                new ParticipateGoodsFundingRequest(1, PaymentMethod.VIRTUAL_ACCOUNT)).id();
        fakePgPaymentGateway.alwaysPending(orderIdPending);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime farPast = now.minusMinutes(10);

        // 참여 시점에 고른 결제수단이 Orders -> Payment로 그대로 전달됐는지 확인한다.
        assertThat(paymentRepository.findByOrderId(orderIdSucceeds).orElseThrow().getPaymentMethod())
                .isEqualTo(PaymentMethod.CARD);
        assertThat(paymentRepository.findByOrderId(orderIdPending).orElseThrow().getPaymentMethod())
                .isEqualTo(PaymentMethod.VIRTUAL_ACCOUNT);

        // 1차 시도: 카드는 즉시 성공, 무통장입금은 PENDING이라 outbox row는 아직 완결되지 않는다.
        int firstAttemptProcessed = paymentFanOutRelayScheduler.runOnce(now, farPast, "worker-b1");
        assertThat(firstAttemptProcessed).isZero();
        assertThat(paymentRepository.findByOrderId(orderIdSucceeds).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentRepository.findByOrderId(orderIdPending).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REQUESTED);

        OutboxEvent afterFirstAttempt = fanOutEventOf(fundingId);
        assertThat(afterFirstAttempt.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        // ADR-0006 축 1 핵심: PENDING은 실패가 아니므로 재시도 예산을 소모하지 않는다.
        assertThat(afterFirstAttempt.getAttemptCount()).isZero();
        assertThat(afterFirstAttempt.getNextAttemptAt())
                .isEqualTo(now.plusSeconds(PaymentFanOutRelayScheduler.PENDING_RECHECK_INTERVAL.toSeconds()));

        // 재확인 간격만큼 가상 시계를 이동시켜 여러 사이클을 돌려도(입금이 계속 안 된 상태) 여전히
        // attemptCount는 0으로 유지되고, 이미 성공한 결제는 다시 호출되지 않는다.
        LocalDateTime secondCheck = now.plusSeconds(PaymentFanOutRelayScheduler.PENDING_RECHECK_INTERVAL.toSeconds() + 1);
        paymentFanOutRelayScheduler.runOnce(secondCheck, farPast, "worker-b1");
        assertThat(fanOutEventOf(fundingId).getAttemptCount()).isZero();
        assertThat(fakePgPaymentGateway.callCountFor(orderIdSucceeds)).isEqualTo(1);
        assertThat(fakePgPaymentGateway.callCountFor(orderIdPending)).isEqualTo(2);

        // 입금이 확인됐다고 가정(웹훅 대신 다음 폴링에서 성공으로 바뀌는 상황을 시뮬레이션)하고
        // 재확인 사이클을 한 번 더 돌리면 정상 종결된다.
        fakePgPaymentGateway.stopPending(orderIdPending);
        LocalDateTime thirdCheck = secondCheck.plusSeconds(PaymentFanOutRelayScheduler.PENDING_RECHECK_INTERVAL.toSeconds() + 1);
        int thirdAttemptProcessed = paymentFanOutRelayScheduler.runOnce(thirdCheck, farPast, "worker-b1");

        assertThat(thirdAttemptProcessed).isEqualTo(1);
        assertThat(paymentRepository.findByOrderId(orderIdPending).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        OutboxEvent finalEvent = fanOutEventOf(fundingId);
        assertThat(finalEvent.getStatus()).isEqualTo(OutboxEventStatus.PROCESSED);
        assertThat(finalEvent.getAttemptCount()).isZero(); // 끝까지 재시도 예산을 한 번도 소모하지 않았다.
    }

    @Test
    void A5_claim부터_처리완료까지_지연을_측정한다() {
        int sampleSize = 20;
        List<Long> latenciesMillis = new ArrayList<>();

        for (int i = 0; i < sampleSize; i++) {
            User host = createUser("fanout-host5-" + i);
            GoodsFunding funding = createFunding(host, 1, 1);
            Long fundingId = funding.getId();
            // 참여 1건으로 즉시 목표 달성 -> 같은 트랜잭션에서 Payment/outbox row 생성.
            orderService.participateGoodsFunding(
                    createUser("fanout-p5-" + i).getId(), fundingId, new ParticipateGoodsFundingRequest(1, PaymentMethod.CARD));

            LocalDateTime now = LocalDateTime.now();
            long startNanos = System.nanoTime();
            int processed = paymentFanOutRelayScheduler.runOnce(now, now.minusMinutes(10), "worker-latency");
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

            assertThat(processed).isEqualTo(1); // 이번 호출 시점엔 이 event 하나만 claim 가능하다.
            latenciesMillis.add(elapsedMillis);
        }

        List<Long> sorted = latenciesMillis.stream().sorted().toList();
        long p50 = sorted.get((int) (sorted.size() * 0.5));
        long p95 = sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.95)));
        log.info("A5 claim~처리완료 지연(ms) 실측: n={}, p50={}, p95={}, raw={}",
                sorted.size(), p50, p95, sorted);

        // 참고 지표이므로 느슨한 상한만 assert한다(단일 로컬 MySQL Testcontainers, PG는 즉시 응답하는 fake).
        assertThat(p95).isLessThan(2000);
    }

    /**
     * ADR-0004 "향후 실측이 필요한 하위 질문" 후속: LEASE_TIMEOUT을 근거 있는 값으로 잡으려면
     * "참여자 수가 늘수록 한 outbox row를 다 처리하는 데 걸리는 시간이 어떻게 늘어나는가"를 알아야
     * 한다. charge 루프는 순차 호출(PaymentFanOutRelayScheduler.processOne)이므로, 참여자 수(N)와
     * PG 호출 1건당 지연(L)을 변수로 두고 총 처리시간 ≈ N × (L + 릴레이 자체 오버헤드) + 고정
     * 오버헤드 형태의 선형 관계를 실측으로 확인한다. 실제 PG 왕복시간(L)은 아직 관찰 데이터가 없어
     * 여러 값을 대입해보는 감도 분석(sensitivity analysis)이며, "이 프로젝트가 쓸 실제 PG의 L"을
     * 측정한 것은 아니다 — 그건 PG SDK 연동 후 별도로 채워야 한다.
     */
    @Test
    void A6_참여자_수와_PG_지연에_따른_처리_소요시간을_측정한다() {
        int[] participantCounts = {5, 20, 50};
        long[] simulatedLatenciesMillis = {0, 100};

        List<String> rows = new ArrayList<>();
        for (long latencyMillis : simulatedLatenciesMillis) {
            for (int participantCount : participantCounts) {
                fakePgPaymentGateway.reset();
                fakePgPaymentGateway.withLatency(latencyMillis);

                User host = createUser("fanout-host6-" + latencyMillis + "-" + participantCount);
                GoodsFunding funding = createFunding(host, participantCount, 1);
                Long fundingId = funding.getId();
                preParticipate(fundingId, participantCount, "fanout-p6-" + latencyMillis + "-" + participantCount + "-");

                LocalDateTime now = LocalDateTime.now();
                long startNanos = System.nanoTime();
                int processed = paymentFanOutRelayScheduler.runOnce(now, now.minusMinutes(10), "worker-a6");
                long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

                assertThat(processed).isEqualTo(1);
                assertThat(paymentService.findRequestedPaymentIdsByGoodsFundingId(fundingId)).isEmpty();

                double perParticipantMillis = elapsedMillis / (double) participantCount;
                rows.add(String.format(
                        "latency=%dms, N=%d -> 총 %dms (참여자당 %.1fms, 시뮬레이션 지연 대비 오버헤드 %.1fms)",
                        latencyMillis, participantCount, elapsedMillis, perParticipantMillis,
                        perParticipantMillis - latencyMillis));
            }
        }

        log.info("A6 참여자수×PG지연 감도 분석 실측:\n{}", String.join("\n", rows));
    }
}
