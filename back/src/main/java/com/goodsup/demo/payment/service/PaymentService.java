package com.goodsup.demo.payment.service;

import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.orders.domain.OrdersRepository;
import com.goodsup.demo.payment.domain.Payment;
import com.goodsup.demo.payment.domain.PaymentRepository;
import com.goodsup.demo.payment.domain.PaymentStatus;
import com.goodsup.demo.payment.dto.ParticipantOrder;
import com.goodsup.demo.payment.dto.PaymentChargeSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrdersRepository ordersRepository;

    @Transactional
    public void createRequestedPaymentsForFunding(List<ParticipantOrder> participantOrders) {
        for (ParticipantOrder participantOrder : participantOrders) {
            Payment payment = Payment.builder()
                    .orders(ordersRepository.getReferenceById(participantOrder.orderId()))
                    .amount(participantOrder.amount())
                    .paymentMethod(participantOrder.paymentMethod())
                    .build();
            paymentRepository.save(payment);
        }
    }

    @Transactional(readOnly = true)
    public List<Long> findRequestedPaymentIdsByGoodsFundingId(Long goodsFundingId) {
        return paymentRepository.findIdsByOrdersGoodsFundingIdAndStatus(goodsFundingId, PaymentStatus.REQUESTED);
    }

    @Transactional(readOnly = true)
    public PaymentChargeSnapshot loadForCharge(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        return new PaymentChargeSnapshot(payment.getId(), payment.getOrders().getId(), payment.getAmount(),
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
}
