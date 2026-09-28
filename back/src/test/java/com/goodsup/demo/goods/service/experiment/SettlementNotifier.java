package com.goodsup.demo.goods.service.experiment;

import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.notification.domain.Notification;
import com.goodsup.demo.notification.domain.NotificationRepository;
import com.goodsup.demo.notification.domain.NotificationType;
import com.goodsup.demo.orders.domain.OrdersRepository;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementNotifier {

    private final OrdersRepository ordersRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public void notifyFundingFailed(GoodsFunding goodsFunding) {
        for (Long userId : ordersRepository.findDistinctUserIdsByGoodsFundingId(goodsFunding.getId())) {
            if (notificationRepository.existsByUserIdAndGoodsFundingIdAndType(
                    userId, goodsFunding.getId(), NotificationType.FUNDING_FAILED)) {
                continue;
            }
            User user = userRepository.getReferenceById(userId);
            try {
                notificationRepository.save(Notification.builder()
                        .user(user)
                        .goodsFunding(goodsFunding)
                        .type(NotificationType.FUNDING_FAILED)
                        .build());
            } catch (DataIntegrityViolationException e) {
                // 동시 배치 실행 등으로 유니크 제약에 걸린 경우 — 이미 생성된 것으로 간주하고 무시
            }
        }
    }
}
