package com.goodsup.demo.payment.service;

import com.goodsup.demo.common.AbstractConcurrencyIntegrationTest;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.orders.domain.Orders;
import com.goodsup.demo.orders.domain.OrdersRepository;
import com.goodsup.demo.orders.dto.request.ParticipateGoodsFundingRequest;
import com.goodsup.demo.orders.service.OrderService;
import com.goodsup.demo.payment.domain.OutboxEventRepository;
import com.goodsup.demo.payment.domain.Payment;
import com.goodsup.demo.payment.domain.PaymentMethod;
import com.goodsup.demo.payment.domain.PaymentRepository;
import com.goodsup.demo.payment.domain.PaymentStatus;
import com.goodsup.demo.payment.domain.PgChargeStatus;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentBulkFailConcurrencyTest extends AbstractConcurrencyIntegrationTest {

    private static final int PARTICIPANTS = 30;

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

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        ordersRepository.deleteAllInBatch();
        goodsFundingRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private Long fundingWithRequestedPayments() {
        User host = userRepository.saveAndFlush(User.builder()
                .email("host@bulk-fail-test.com").password("password").nickname("host").build());
        GoodsFunding funding = goodsFundingRepository.saveAndFlush(GoodsFunding.builder()
                .host(host)
                .title("벌크 실패 처리 실험용")
                .description("설명")
                .price(1000)
                .targetQuantity(PARTICIPANTS)
                .maxQuantityPerUser(1)
                .deadlineAt(LocalDateTime.now().plusMinutes(10))
                .build());
        for (int i = 0; i < PARTICIPANTS; i++) {
            User participant = userRepository.saveAndFlush(User.builder()
                    .email("p" + i + "@bulk-fail-test.com").password("password").nickname("p" + i).build());
            orderService.participateGoodsFunding(
                    participant.getId(), funding.getId(), new ParticipateGoodsFundingRequest(1, PaymentMethod.CARD));
        }
        return funding.getId();
    }

    @Test
    void 남은_REQUESTED만_FAILED로_바뀌고_이미_SUCCEEDED인_결제는_유지된다() {
        Long fundingId = fundingWithRequestedPayments();
        List<Long> paymentIds = paymentService.findRequestedPaymentIdsByGoodsFundingId(fundingId);
        assertThat(paymentIds).hasSize(PARTICIPANTS);
        paymentService.markSucceeded(paymentIds.get(0), "PG-TX-KEEP");

        paymentService.markAllRemainingRequestedAsFailed(fundingId);

        List<Payment> payments = paymentRepository.findAll();
        assertThat(payments).filteredOn(p -> p.getStatus() == PaymentStatus.SUCCEEDED).hasSize(1);
        assertThat(payments).filteredOn(p -> p.getStatus() == PaymentStatus.FAILED).hasSize(PARTICIPANTS - 1);
        assertThat(payments).noneMatch(p -> p.getStatus() == PaymentStatus.REQUESTED);
        assertThat(paymentService.findRequestedPaymentIdsByGoodsFundingId(fundingId)).isEmpty();
    }

    @Test
    void 웹훅_성공과_벌크_FAILED가_경합해도_각_결제는_하나의_종결_상태로만_끝난다() throws Exception {
        Long fundingId = fundingWithRequestedPayments();
        List<Long> orderIds = ordersRepository.findAll().stream().map(Orders::getId).toList();

        ExecutorService executor = Executors.newFixedThreadPool(PARTICIPANTS + 1);
        CountDownLatch ready = new CountDownLatch(PARTICIPANTS + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Long orderId : orderIds) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                paymentService.applyWebhookResult(orderId, PgChargeStatus.SUCCESS, "PG-TX-" + orderId);
                return null;
            }));
        }
        futures.add(executor.submit(() -> {
            ready.countDown();
            start.await();
            paymentService.markAllRemainingRequestedAsFailed(fundingId);
            return null;
        }));
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        List<Payment> payments = paymentRepository.findAll();
        assertThat(payments).hasSize(PARTICIPANTS);
        assertThat(payments).noneMatch(p -> p.getStatus() == PaymentStatus.REQUESTED);
        assertThat(payments).allSatisfy(p -> {
            if (p.getStatus() == PaymentStatus.SUCCEEDED) {
                assertThat(p.getPgTransactionId()).isNotNull();
            } else {
                assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
                assertThat(p.getPgTransactionId()).isNull();
            }
        });
    }
}
