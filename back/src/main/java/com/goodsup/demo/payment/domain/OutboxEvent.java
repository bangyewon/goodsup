package com.goodsup.demo.payment.domain;

import com.goodsup.demo.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "outbox_event",
        indexes = @Index(name = "idx_outbox_status_next_attempt", columnList = "status, next_attempt_at"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"event_type", "aggregate_id"}, name = "uk_outbox_event_type_aggregate"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private OutboxEventType eventType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxEventStatus status;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Builder
    private OutboxEvent(OutboxEventType eventType, Long aggregateId, LocalDateTime nextAttemptAt) {
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.status = OutboxEventStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = nextAttemptAt;
    }

    public static OutboxEvent forPaymentFanOut(Long goodsFundingId, LocalDateTime now) {
        return OutboxEvent.builder()
                .eventType(OutboxEventType.PAYMENT_FANOUT_REQUESTED)
                .aggregateId(goodsFundingId)
                .nextAttemptAt(now)
                .build();
    }

    public boolean claim(String workerId, LocalDateTime now, LocalDateTime staleBefore) {
        boolean pendingAndDue = this.status == OutboxEventStatus.PENDING && !this.nextAttemptAt.isAfter(now);
        boolean staleProcessing = this.status == OutboxEventStatus.PROCESSING
                && this.claimedAt != null && !this.claimedAt.isAfter(staleBefore);
        if (!pendingAndDue && !staleProcessing) {
            return false;
        }
        this.status = OutboxEventStatus.PROCESSING;
        this.claimedBy = workerId;
        this.claimedAt = now;
        return true;
    }

    public void markProcessed() {
        if (this.status != OutboxEventStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태에서만 완료 처리할 수 있습니다: id=" + this.id);
        }
        this.status = OutboxEventStatus.PROCESSED;
    }

    public void markPendingForRetry(String error, LocalDateTime now, Duration baseBackoff, Duration maxBackoff, int maxAttempts) {
        if (this.status != OutboxEventStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태에서만 재시도 대기로 전환할 수 있습니다: id=" + this.id);
        }
        this.attemptCount++;
        this.lastError = error;
        if (this.attemptCount >= maxAttempts) {
            this.status = OutboxEventStatus.FAILED;
            return;
        }
        long delaySeconds = Math.min(baseBackoff.toSeconds() * (1L << attemptCount), maxBackoff.toSeconds());
        this.status = OutboxEventStatus.PENDING;
        this.nextAttemptAt = now.plusSeconds(delaySeconds);
    }
}
