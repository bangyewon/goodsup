package com.goodsup.demo.payment.service;

import com.goodsup.demo.payment.dto.PaymentChargeSnapshot;
import com.goodsup.demo.payment.dto.PgChargeRequest;
import com.goodsup.demo.payment.dto.PgChargeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentFanOutRelayScheduler {

    // 근거: docs/adr/0004-payment-fanout-trigger-strategy.md "결정 — 재시도/lease 상수 확정" 참고.
    // A6 실측(릴레이 오버헤드·N 비례 확인) + 사용자가 확정한 정책 가정(PG 타임아웃 5초,
    // 최대 참여자 1000명, 재시도 SLA 약 1시간)으로부터 역산했다.
    static final int MAX_ATTEMPTS = 11;
    static final Duration LEASE_TIMEOUT = Duration.ofMinutes(90);
    static final Duration BASE_BACKOFF = Duration.ofSeconds(10);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final OutboxEventService outboxEventService;
    private final PaymentService paymentService;
    private final PgPaymentGateway pgPaymentGateway;

    @Scheduled(cron = "${goodsup.payment.fanout-relay.cron:0 * * * * *}")
    public void run() {
        String workerId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        int processed = runOnce(now, now.minus(LEASE_TIMEOUT), workerId);
        log.info("결제 fan-out 릴레이 완료: workerId={}, 처리 완료 {}건", workerId, processed);
    }

    public int runOnce(LocalDateTime now, LocalDateTime staleBefore, String workerId) {
        List<Long> candidateIds = outboxEventService.findClaimableCandidateIds(now, staleBefore);
        int processed = 0;
        for (Long outboxEventId : candidateIds) {
            try {
                if (processOne(outboxEventId, now, staleBefore, workerId)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                log.warn("결제 fan-out 처리 실패: outboxEventId={}", outboxEventId, e);
            }
        }
        return processed;
    }

    private boolean processOne(Long outboxEventId, LocalDateTime now, LocalDateTime staleBefore, String workerId) {
        Optional<Long> goodsFundingId = outboxEventService.tryClaim(outboxEventId, workerId, now, staleBefore);
        if (goodsFundingId.isEmpty()) {
            return false;
        }

        List<Long> paymentIds = paymentService.findRequestedPaymentIdsByGoodsFundingId(goodsFundingId.get());
        boolean allTerminal = true;
        for (Long paymentId : paymentIds) {
            if (!chargeOne(paymentId)) {
                allTerminal = false;
            }
        }

        if (allTerminal) {
            outboxEventService.markProcessed(outboxEventId);
            return true;
        }

        boolean terminallyFailed = outboxEventService.markPendingForRetry(
                outboxEventId, "일부 결제 미완료", now, BASE_BACKOFF, MAX_BACKOFF, MAX_ATTEMPTS);
        if (terminallyFailed) {
            paymentService.markAllRemainingRequestedAsFailed(goodsFundingId.get());
            log.error("결제 fan-out 최대 재시도 초과: outboxEventId={}, goodsFundingId={}", outboxEventId, goodsFundingId.get());
        }
        return false;
    }

    /**
     * @return 이 결제가 최종적으로 터미널 상태(SUCCEEDED)에 도달했으면 true.
     */
    private boolean chargeOne(Long paymentId) {
        PaymentChargeSnapshot snapshot = paymentService.loadForCharge(paymentId);
        if (snapshot.alreadyTerminal()) {
            return true;
        }
        String idempotencyKey = "order-" + snapshot.orderId();
        try {
            PgChargeResult result = pgPaymentGateway.charge(
                    new PgChargeRequest(snapshot.orderId(), snapshot.amount(), idempotencyKey));
            if (result.success()) {
                paymentService.markSucceeded(paymentId, result.pgTransactionId());
                return true;
            }
            log.warn("PG 결제 승인 실패: paymentId={}, error={}", paymentId, result.errorMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("PG 결제 승인 호출 중 예외: paymentId={}", paymentId, e);
            return false;
        }
    }
}
