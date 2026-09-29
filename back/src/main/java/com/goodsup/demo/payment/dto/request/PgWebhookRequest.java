package com.goodsup.demo.payment.dto.request;

import com.goodsup.demo.payment.domain.PgChargeStatus;
import jakarta.validation.constraints.NotNull;

public record PgWebhookRequest(
        @NotNull(message = "orderId는 필수입니다.")
        Long orderId,
        @NotNull(message = "결제 상태는 필수입니다.")
        PgChargeStatus status,
        String pgTransactionId
) {
}
