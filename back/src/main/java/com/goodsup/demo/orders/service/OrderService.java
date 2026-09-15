package com.goodsup.demo.orders.service;

import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.goods.domain.GoodsFundingStatus;
import com.goodsup.demo.orders.domain.Orders;
import com.goodsup.demo.orders.domain.OrdersRepository;
import com.goodsup.demo.orders.dto.request.ParticipateGoodsFundingRequest;
import com.goodsup.demo.orders.dto.response.OrderResponse;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final UserRepository userRepository;
    private final GoodsFundingRepository goodsFundingRepository;
    private final OrdersRepository ordersRepository;

    @Transactional
    public OrderResponse participateGoodsFunding(Long userId, Long goodsFundingId,ParticipateGoodsFundingRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));

        // ADR-0001: 공구 참여(재고 차감)는 DB 비관적 락(findByIdForUpdate)으로 동시성을 제어
        GoodsFunding goodsFunding = goodsFundingRepository.findByIdForUpdate(goodsFundingId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));

        if (goodsFunding.getStatus() != GoodsFundingStatus.RECRUITING
                || !goodsFunding.getDeadlineAt().isAfter(LocalDateTime.now())) {
            throw new GoodsException(ErrorCode.RECRUITING_CLOSED);
        }
        int alreadyOrderedQuantity = ordersRepository.sumQuantityByGoodsFundingIdAndUserId(goodsFundingId, userId);
        if (goodsFunding.getMaxQuantityPerUser() < alreadyOrderedQuantity + request.quantity()) {
            throw new GoodsException(ErrorCode.MAX_QUANTITY_OVER);
        }

        goodsFunding.increaseQuantityAndCloseIfNeeded(request.quantity());

        Orders order = ordersRepository.save(Orders.builder()
                .goodsFunding(goodsFunding)
                .user(user)
                .quantity(request.quantity())
                .build());

        return OrderResponse.from(order);
    }
}
