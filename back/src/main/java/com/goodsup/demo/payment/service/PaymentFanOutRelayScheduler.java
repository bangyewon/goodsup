package com.goodsup.demo.payment.service;

import com.goodsup.demo.payment.domain.ChargeOutcome;
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

    static final int MAX_ATTEMPTS = 11;
    static final Duration LEASE_TIMEOUT = Duration.ofMinutes(90);
    static final Duration BASE_BACKOFF = Duration.ofSeconds(10);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    static final Duration PENDING_RECHECK_INTERVAL = Duration.ofMinutes(30);

    private final OutboxEventService outboxEventService;
    private final PaymentService paymentService;
    private final PaymentChargeExecutor paymentChargeExecutor;

    @Scheduled(cron = "${goodsup.payment.fanout-relay.cron:0 * * * * *}")
    public void run() {
        String workerId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        int processed = runOnce(now, now.minus(LEASE_TIMEOUT), workerId);
        log.info("결제 fan-out 릴레이 완료: workerId={}, 처리 완료 {}건", workerId, processed);
    }

    int runOnce(LocalDateTime now, LocalDateTime staleBefore, String workerId) {
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
        boolean allDone = true;
        boolean anyAwaitingPg = false;
        for (Long paymentId : paymentIds) {
            ChargeOutcome outcome = paymentChargeExecutor.charge(paymentId);
            if (outcome != ChargeOutcome.DONE) {
                allDone = false;
            }
            if (outcome == ChargeOutcome.AWAITING_PG) {
                anyAwaitingPg = true;
            }
        }

        if (allDone) {
            outboxEventService.markProcessed(outboxEventId);
            return true;
        }

        if (anyAwaitingPg) {
            outboxEventService.markPendingRecheck(outboxEventId, now, PENDING_RECHECK_INTERVAL);
            return false;
        }

        boolean terminallyFailed = outboxEventService.markPendingForRetry(
                outboxEventId, "일부 결제 미완료", now, BASE_BACKOFF, MAX_BACKOFF, MAX_ATTEMPTS);
        if (terminallyFailed) {
            paymentService.markAllRemainingRequestedAsFailed(goodsFundingId.get());
            log.error("결제 fan-out 최대 재시도 초과: outboxEventId={}, goodsFundingId={}", outboxEventId, goodsFundingId.get());
        }
        return false;
    }
}
