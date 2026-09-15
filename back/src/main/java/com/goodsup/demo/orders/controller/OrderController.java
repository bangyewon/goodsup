package com.goodsup.demo.orders.controller;

import com.goodsup.demo.common.apiResponse.CustomApiResponse;
import com.goodsup.demo.orders.dto.request.ParticipateGoodsFundingRequest;
import com.goodsup.demo.orders.dto.response.OrderResponse;
import com.goodsup.demo.orders.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/goods-fundings/{goodsFundingId}/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<CustomApiResponse<OrderResponse>> participateGoodsFunding(
            @RequestHeader(value = "userId") Long userId,
            @PathVariable Long goodsFundingId,
            @Valid @RequestBody ParticipateGoodsFundingRequest request) {
        OrderResponse response = orderService.participateGoodsFunding(
                userId, goodsFundingId,request);
        return CustomApiResponse.success(response, HttpStatus.CREATED.value(), "공동구매 참여가 완료됐습니다.")
                .toResponseEntity();
    }
}
