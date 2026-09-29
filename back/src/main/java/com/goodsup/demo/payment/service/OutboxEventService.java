package com.goodsup.demo.payment.service;

import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.payment.domain.OutboxEvent;
import com.goodsup.demo.payment.domain.OutboxEventRepository;
import com.goodsup.demo.payment.domain.OutboxEventStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public void recordPaymentFanOutRequested(Long goodsFundingId, LocalDateTime now) {
        outboxEventRepository.save(OutboxEvent.forPaymentFanOut(goodsFundingId, now));
    }

    @Transactional(readOnly = true)
    public List<Long> findClaimableCandidateIds(LocalDateTime now, LocalDateTime staleBefore) {
        return outboxEventRepository.findClaimableCandidateIds(now, staleBefore);
    }

    @Transactional
    public Optional<Long> tryClaim(Long outboxEventId, String workerId, LocalDateTime now, LocalDateTime staleBefore) {
        OutboxEvent outboxEvent = outboxEventRepository.findByIdForUpdate(outboxEventId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        if (!outboxEvent.claim(workerId, now, staleBefore)) {
            return Optional.empty();
        }
        return Optional.of(outboxEvent.getAggregateId());
    }

    @Transactional
    public void markProcessed(Long outboxEventId) {
        OutboxEvent outboxEvent = outboxEventRepository.findByIdForUpdate(outboxEventId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        outboxEvent.markProcessed();
    }

    @Transactional
    public void markPendingRecheck(Long outboxEventId, LocalDateTime now, Duration recheckInterval) {
        OutboxEvent outboxEvent = outboxEventRepository.findByIdForUpdate(outboxEventId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        outboxEvent.markPendingRecheck(now, recheckInterval);
    }

    @Transactional
    public boolean markPendingForRetry(Long outboxEventId, String error, LocalDateTime now,
                                        Duration baseBackoff, Duration maxBackoff, int maxAttempts) {
        OutboxEvent outboxEvent = outboxEventRepository.findByIdForUpdate(outboxEventId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        outboxEvent.markPendingForRetry(error, now, baseBackoff, maxBackoff, maxAttempts);
        return outboxEvent.getStatus() == OutboxEventStatus.FAILED;
    }
}
