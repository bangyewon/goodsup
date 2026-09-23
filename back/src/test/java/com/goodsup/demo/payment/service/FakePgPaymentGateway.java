package com.goodsup.demo.payment.service;

import com.goodsup.demo.payment.dto.PgChargeRequest;
import com.goodsup.demo.payment.dto.PgChargeResult;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADR-0004 실측용 PG 게이트웨이 더블. 특정 주문의 응답을 인위적으로 차단/지연시키거나
 * 강제로 실패시켜, claim/lease/재시도 동시성 시나리오를 실제 대기 없이 결정론적으로 재현한다.
 */
class FakePgPaymentGateway implements PgPaymentGateway {

    private final Map<Long, AtomicInteger> callCounts = new ConcurrentHashMap<>();
    private final Set<Long> alwaysFailOrderIds = ConcurrentHashMap.newKeySet();
    private volatile Long blockedOrderId;
    private volatile CountDownLatch blockReleaseLatch;
    private volatile CountDownLatch blockStartedSignal;

    @Override
    public PgChargeResult charge(PgChargeRequest request) {
        callCounts.computeIfAbsent(request.orderId(), id -> new AtomicInteger()).incrementAndGet();

        if (request.orderId().equals(blockedOrderId)) {
            if (blockStartedSignal != null) {
                blockStartedSignal.countDown();
            }
            awaitUninterruptibly(blockReleaseLatch);
        }

        if (alwaysFailOrderIds.contains(request.orderId())) {
            return PgChargeResult.failure("의도적 실패(테스트)");
        }
        return PgChargeResult.success("FAKE-PG-" + UUID.randomUUID());
    }

    int callCountFor(Long orderId) {
        return callCounts.getOrDefault(orderId, new AtomicInteger()).get();
    }

    /** 이후 이 orderId에 대한 charge 호출은 releaseLatch가 열릴 때까지 블록되고, 블록 시작 시 startedSignal을 카운트다운한다. */
    void blockOrderUntil(Long orderId, CountDownLatch releaseLatch, CountDownLatch startedSignal) {
        this.blockedOrderId = orderId;
        this.blockReleaseLatch = releaseLatch;
        this.blockStartedSignal = startedSignal;
    }

    void alwaysFail(Long orderId) {
        alwaysFailOrderIds.add(orderId);
    }

    void reset() {
        callCounts.clear();
        alwaysFailOrderIds.clear();
        blockedOrderId = null;
        blockReleaseLatch = null;
        blockStartedSignal = null;
    }

    private void awaitUninterruptibly(CountDownLatch latch) {
        if (latch == null) {
            return;
        }
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
