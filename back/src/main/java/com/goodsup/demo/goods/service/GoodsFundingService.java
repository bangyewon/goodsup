package com.goodsup.demo.goods.service;

import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.goods.dto.request.RegisterGoodsFundingRequest;
import com.goodsup.demo.goods.dto.response.ShowGoodsFundingListResponse;
import com.goodsup.demo.goods.dto.response.ShowGoodsFundingResponse;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GoodsFundingService {
    private final GoodsFundingRepository goodsFundingRepository;
    private final UserRepository userRepository;

    @Transactional
    public long registerGoodsFunding(Long userId, RegisterGoodsFundingRequest request) {
        User user = userRepository.findById(userId).orElseThrow(
                () -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        GoodsFunding goodsFunding = request.toEntity(user);
        goodsFundingRepository.save(goodsFunding);
        return goodsFunding.getId();
    }

    @Transactional(readOnly = true)
    public ShowGoodsFundingResponse showGoodsFunding(Long goodsId) {
        GoodsFunding goodsFunding = goodsFundingRepository.findById(goodsId).orElseThrow(
                () -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        return ShowGoodsFundingResponse.from(goodsFunding);
    }
    @Transactional(readOnly = true)
    public Page<ShowGoodsFundingListResponse> showGoodsFundingList(Pageable pageable) {
        return goodsFundingRepository.findAllWithHost(pageable)
                .map(ShowGoodsFundingListResponse::from);
    }
}
