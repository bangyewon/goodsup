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

@Entity
@Getter
@Table(name = "payment",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"order_id"}, name = "uk_payment_order_id")},
        indexes = {@Index(name = "idx_payment_goods_funding_status", columnList = "goods_funding_id, status")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "goods_funding_id", nullable = false)
    private Long goodsFundingId;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @Column(name = "pg_transaction_id")
    private String pgTransactionId;

    @Builder
    private Payment(Long orderId, Long goodsFundingId, int amount, PaymentMethod paymentMethod) {
        this.orderId = orderId;
        this.goodsFundingId = goodsFundingId;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.status = PaymentStatus.REQUESTED;
    }

    public boolean markSucceeded(String pgTransactionId) {
        if (this.status != PaymentStatus.REQUESTED) {
            return false;
        }
        this.status = PaymentStatus.SUCCEEDED;
        this.pgTransactionId = pgTransactionId;
        return true;
    }

    public boolean markFailed() {
        if (this.status != PaymentStatus.REQUESTED) {
            return false;
        }
        this.status = PaymentStatus.FAILED;
        return true;
    }
}
