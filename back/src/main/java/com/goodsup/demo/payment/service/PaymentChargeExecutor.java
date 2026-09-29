package com.goodsup.demo.payment.service;

import com.goodsup.demo.payment.domain.ChargeOutcome;
import com.goodsup.demo.payment.dto.PaymentChargeSnapshot;
import com.goodsup.demo.payment.dto.PgChargeRequest;
import com.goodsup.demo.payment.dto.PgChargeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentChargeExecutor {

    private final PaymentService paymentService;
    private final PgPaymentGateway pgPaymentGateway;

    public ChargeOutcome charge(Long paymentId) {
        PaymentChargeSnapshot snapshot = paymentService.loadForCharge(paymentId);
        if (snapshot.alreadyTerminal()) {
            return ChargeOutcome.DONE;
        }
        PgChargeResult result;
        try {
            result = pgPaymentGateway.charge(PgChargeRequest.from(snapshot));
        } catch (RuntimeException e) {
            log.warn("PG 결제 승인 호출 중 예외: paymentId={}", paymentId, e);
            return ChargeOutcome.RETRY_NEEDED;
        }

        return switch (result.status()) {
            case SUCCESS -> handleSuccess(paymentId, result);
            case PENDING -> handlePending(paymentId);
            case FAILURE -> handleFailure(paymentId, result);
        };
    }

    private ChargeOutcome handleSuccess(Long paymentId, PgChargeResult result) {
        boolean applied = paymentService.markSucceeded(paymentId, result.pgTransactionId());
        if (!applied) {
            log.error(
                    "PG는 SUCCESS를 응답했으나 결제 상태 갱신 실패(이미 REQUESTED 아님) - "
                            + "정산/환불 상태와 불일치 가능성 있어 수동 확인 필요: paymentId={}, pgTransactionId={}",
                    paymentId, result.pgTransactionId());
        }
        return ChargeOutcome.DONE;
    }

    private ChargeOutcome handlePending(Long paymentId) {
        log.info("PG 결제 대기 중(PENDING): paymentId={}", paymentId);
        return ChargeOutcome.AWAITING_PG;
    }

    private ChargeOutcome handleFailure(Long paymentId, PgChargeResult result) {
        log.warn("PG 결제 승인 실패: paymentId={}, error={}", paymentId, result.errorMessage());
        return ChargeOutcome.RETRY_NEEDED;
    }
}
