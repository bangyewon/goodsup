package com.goodsup.demo.payment.service;

import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.payment.domain.Payment;
import com.goodsup.demo.payment.domain.PaymentRepository;
import com.goodsup.demo.payment.domain.PaymentStatus;
import com.goodsup.demo.payment.domain.PgChargeStatus;
import com.goodsup.demo.payment.dto.ParticipantOrder;
import com.goodsup.demo.payment.dto.PaymentChargeSnapshot;
import com.goodsup.demo.payment.dto.response.PaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;

    @Transactional
    public void createRequestedPaymentsForFunding(Long goodsFundingId, List<ParticipantOrder> participantOrders) {
        for (ParticipantOrder participantOrder : participantOrders) {
            Payment payment = Payment.builder()
                    .orderId(participantOrder.orderId())
                    .goodsFundingId(goodsFundingId)
                    .amount(participantOrder.amount())
                    .paymentMethod(participantOrder.paymentMethod())
                    .build();
            paymentRepository.save(payment);
        }
    }

    @Transactional(readOnly = true)
    public List<Long> findRequestedPaymentIdsByGoodsFundingId(Long goodsFundingId) {
        return paymentRepository.findIdsByGoodsFundingIdAndStatus(goodsFundingId, PaymentStatus.REQUESTED);
    }

    @Transactional(readOnly = true)
    public PaymentChargeSnapshot loadForCharge(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        return new PaymentChargeSnapshot(payment.getId(), payment.getOrderId(), payment.getAmount(),
                payment.getPaymentMethod(), payment.getStatus() != PaymentStatus.REQUESTED);
    }

    @Transactional
    public boolean markSucceeded(Long paymentId, String pgTransactionId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        return payment.markSucceeded(pgTransactionId);
    }

    @Transactional
    public void markAllRemainingRequestedAsFailed(Long goodsFundingId) {
        List<Long> remainingPaymentIds = findRequestedPaymentIdsByGoodsFundingId(goodsFundingId);
        for (Long paymentId : remainingPaymentIds) {
            paymentRepository.findByIdForUpdate(paymentId).ifPresent(Payment::markFailed);
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getByOrderId(Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        return PaymentResponse.from(payment);
    }

    @Transactional
    public void applyWebhookResult(Long orderId, PgChargeStatus status, String pgTransactionId) {
        Long paymentId = paymentRepository.findByOrderId(orderId)
                .map(Payment::getId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));

        boolean applied = switch (status) {
            case SUCCESS -> payment.markSucceeded(pgTransactionId);
            case FAILURE -> payment.markFailed();
            case PENDING -> false;
        };
        log.info("결제 웹훅 처리: orderId={}, status={}, applied={}", orderId, status, applied);
    }
}
