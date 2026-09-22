package com.goodsup.demo.payment.service;

import com.goodsup.demo.payment.dto.PgChargeRequest;
import com.goodsup.demo.payment.dto.PgChargeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class LoggingPgPaymentGateway implements PgPaymentGateway {

    @Override
    public PgChargeResult charge(PgChargeRequest request) {
        String pgTransactionId = "PG-" + UUID.randomUUID();
        log.info("PG 결제 승인 요청(placeholder): orderId={}, amount={}, idempotencyKey={}, pgTransactionId={}",
                request.orderId(), request.amount(), request.idempotencyKey(), pgTransactionId);
        return PgChargeResult.success(pgTransactionId);
    }
}
