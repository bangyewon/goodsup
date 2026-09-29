package com.goodsup.demo.payment.controller;

import com.goodsup.demo.common.apiResponse.CustomApiResponse;
import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.payment.dto.request.PgWebhookRequest;
import com.goodsup.demo.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentService paymentService;

    @Value("${goodsup.payment.webhook.secret}")
    private String webhookSecret;

    @PostMapping
    public ResponseEntity<CustomApiResponse<Void>> receiveWebhook(
            @RequestHeader("X-PG-Signature") String signature,
            @Valid @RequestBody PgWebhookRequest request) {
        if (!MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                webhookSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new GoodsException(ErrorCode.FORBIDDEN);
        }

        paymentService.applyWebhookResult(request.orderId(), request.status(), request.pgTransactionId());
        return CustomApiResponse.<Void>success(null, HttpStatus.OK.value(), "웹훅 처리가 완료됐습니다.")
                .toResponseEntity();
    }
}
