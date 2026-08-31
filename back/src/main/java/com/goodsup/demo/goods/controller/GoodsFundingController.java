package com.goodsup.demo.goods.controller;

import com.goodsup.demo.common.apiResponse.CustomApiResponse;
import com.goodsup.demo.goods.dto.request.RegisterGoodsFundingRequest;
import com.goodsup.demo.goods.dto.response.ShowGoodsFundingListResponse;
import com.goodsup.demo.goods.dto.response.ShowGoodsFundingResponse;
import com.goodsup.demo.goods.service.GoodsFundingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/goods-fundings")
@RequiredArgsConstructor
public class GoodsFundingController {
    private final GoodsFundingService goodsFundingService;
    @PostMapping
    public ResponseEntity<CustomApiResponse<Long>> registerGoodsFunding(
            @RequestHeader(value = "userId") Long userId,
            @Valid @RequestBody RegisterGoodsFundingRequest request) {
        Long goodsId = goodsFundingService.registerGoodsFunding(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CustomApiResponse.success(goodsId, HttpStatus.CREATED.value(), "공동구매 창이 생성됐습니다."));
    }
    @GetMapping("/{goodsId}")
    public ResponseEntity<CustomApiResponse<ShowGoodsFundingResponse>> showGoodsFunding(@PathVariable Long goodsId) {
        ShowGoodsFundingResponse response = goodsFundingService.showGoodsFunding(goodsId);
        return ResponseEntity.ok(
                CustomApiResponse.success(response, HttpStatus.OK.value(), "공동구매 게시물 조회를 성공했습니다."));
    }
    @GetMapping
    public ResponseEntity<CustomApiResponse<Page<ShowGoodsFundingListResponse>>> showGoodsFundingList(
            @PageableDefault(size = 20, sort = "deadlineAt") Pageable pageable) {
        Page<ShowGoodsFundingListResponse> response = goodsFundingService.showGoodsFundingList(pageable);
        return ResponseEntity.ok(
                CustomApiResponse.success(response, HttpStatus.OK.value(), "공동구매 목록 조회를 성공했습니다."));
    }
}
