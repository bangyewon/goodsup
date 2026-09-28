package com.goodsup.demo.orders.service;

import com.goodsup.demo.common.AbstractConcurrencyIntegrationTest;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.orders.domain.OrdersRepository;
import com.goodsup.demo.orders.dto.request.ParticipateGoodsFundingRequest;
import com.goodsup.demo.payment.domain.PaymentMethod;
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

/**
 * ADR-0001에서 채택한 DB 비관적 락 기반 공구 참여 동시성 제어를 검증하는 통합 테스트.
 * 목표 수량보다 많은 동시 참여 요청을 보내도 currentQuantity가 targetQuantity를 초과하지 않아야 한다.
 */
class OrderConcurrencyIntegrationTest extends AbstractConcurrencyIntegrationTest {

    private static final int TARGET_QUANTITY = 30;
    private static final int CONCURRENT_REQUESTS = 100;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GoodsFundingRepository goodsFundingRepository;
    @Autowired
    private OrdersRepository ordersRepository;
    @Autowired
    private OrderService orderService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    /**
     * 목표 수량을 채우면 참여 트랜잭션이 Payment/outbox row까지 함께 생성한다(ADR-0004).
     * 싱글턴 컨테이너를 다른 테스트 클래스와 공유하므로, FK 순서(payment -> outbox_event ->
     * orders -> goods_funding -> user)로 직접 정리하지 않으면 이 데이터가 다른 클래스의
     * outbox 릴레이 테스트에 잘못 섞여 들어간다(ADR-0004 A3/A4 실측 중 재현: 정리 누락으로
     * 남은 이 테스트의 outbox row를 다른 테스트가 자기 것과 함께 집계해버림).
     */
    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        ordersRepository.deleteAllInBatch();
        goodsFundingRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void 동시에_목표_수량보다_많은_참여_요청이_와도_재고를_초과하지_않는다() throws InterruptedException {
        User host = userRepository.saveAndFlush(User.builder()
                .email("host@concurrency-test.com")
                .password("password")
                .nickname("host")
                .build());

        GoodsFunding goodsFunding = goodsFundingRepository.saveAndFlush(GoodsFunding.builder()
                .host(host)
                .title("동시성 테스트 굿즈펀딩")
                .description("설명")
                .price(1000)
                .targetQuantity(TARGET_QUANTITY)
                .maxQuantityPerUser(1)
                .deadlineAt(LocalDateTime.now().plusDays(1))
                .build());
        Long goodsFundingId = goodsFunding.getId();

        List<Long> participantIds = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            User participant = userRepository.saveAndFlush(User.builder()
                    .email("participant" + i + "@concurrency-test.com")
                    .password("password")
                    .nickname("participant" + i)
                    .build());
            participantIds.add(participant.getId());
        }

        ExecutorService executorService = Executors.newFixedThreadPool(30);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger successCount = new AtomicInteger();

        for (Long participantId : participantIds) {
            executorService.submit(() -> {
                try {
                    startSignal.await();
                    try {
                        orderService.participateGoodsFunding(
                                participantId,goodsFundingId, new ParticipateGoodsFundingRequest(1, PaymentMethod.CARD));
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                        // 재고 소진에 따른 정상적인 거절은 무시한다.
                    }
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
        assertThat(reloaded.getCurrentQuantity()).isEqualTo(TARGET_QUANTITY);
        assertThat(successCount.get()).isEqualTo(TARGET_QUANTITY);
        assertThat(ordersRepository.count()).isEqualTo(TARGET_QUANTITY);
    }
}
