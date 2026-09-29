package com.goodsup.demo.payment.controller;

import com.goodsup.demo.common.apiResponse.CustomApiResponse;
import com.goodsup.demo.payment.dto.request.PgWebhookRequest;
import com.goodsup.demo.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<CustomApiResponse<Void>> receiveWebhook(@Valid @RequestBody PgWebhookRequest request) {
        paymentService.applyWebhookResult(request.orderId(), request.status(), request.pgTransactionId());
        return CustomApiResponse.<Void>success(null, HttpStatus.OK.value(), "웹훅 처리가 완료됐습니다.")
                .toResponseEntity();
    }
}
