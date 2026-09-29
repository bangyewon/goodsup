package com.goodsup.demo.payment.controller;

import com.goodsup.demo.common.apiResponse.CustomApiResponse;
import com.goodsup.demo.payment.dto.response.PaymentResponse;
import com.goodsup.demo.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders/{orderId}/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping
    public ResponseEntity<CustomApiResponse<PaymentResponse>> getPayment(@PathVariable Long orderId) {
        PaymentResponse response = paymentService.getByOrderId(orderId);
        return CustomApiResponse.success(response, HttpStatus.OK.value(), "결제 상태 조회가 완료됐습니다.")
                .toResponseEntity();
    }
}
