package com.goodsup.demo.goods.controller;

import com.goodsup.demo.common.apiResponse.CustomApiResponse;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.dto.request.RegisterGoodsFundingRequest;
import com.goodsup.demo.goods.service.GoodsFundingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/goods-fundings")
@RequiredArgsConstructor
public class GoodsFundingController {
    private final GoodsFundingService goodsFundingService;
    @PostMapping
    public CustomApiResponse<Long> registerGoodsFunding(
            @RequestHeader(value = "userId") Long userId,
            @Valid @RequestBody RegisterGoodsFundingRequest request) {
        Long goodsId = goodsFundingService.registerGoodsFunding(userId, request);
        return CustomApiResponse.success(goodsId,200,"공동구매 창이 생성됐습니다.");
    }
}
