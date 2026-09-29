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
import com.goodsup.demo.payment.service.OutboxEventService;
import com.goodsup.demo.payment.service.PaymentService;
import com.goodsup.demo.payment.dto.ParticipantOrder;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final UserRepository userRepository;
    private final GoodsFundingRepository goodsFundingRepository;
    private final OrdersRepository ordersRepository;
    private final PaymentService paymentService;
    private final OutboxEventService outboxEventService;

    @Transactional
    public OrderResponse participateGoodsFunding(Long userId, Long goodsFundingId,ParticipateGoodsFundingRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));

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

        Orders order = ordersRepository.save(request.toEntity(goodsFunding, user));

        if (goodsFunding.getStatus() == GoodsFundingStatus.FINISHED) {
            List<ParticipantOrder> participantOrders = ordersRepository.findAllByGoodsFundingId(goodsFundingId).stream()
                    .map(o -> new ParticipantOrder(o.getId(), o.getQuantity() * goodsFunding.getPrice(), o.getPaymentMethod()))
                    .toList();
            paymentService.createRequestedPaymentsForFunding(participantOrders);
            outboxEventService.recordPaymentFanOutRequested(goodsFundingId, LocalDateTime.now());
        }

        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public List<Long> findParticipantUserIds(Long goodsFundingId) {
        return ordersRepository.findDistinctUserIdsByGoodsFundingId(goodsFundingId);
    }
}
