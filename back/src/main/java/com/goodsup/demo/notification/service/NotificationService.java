package com.goodsup.demo.notification.service;

import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.service.GoodsFundingService;
import com.goodsup.demo.notification.domain.Notification;
import com.goodsup.demo.notification.domain.NotificationRepository;
import com.goodsup.demo.notification.domain.NotificationType;
import com.goodsup.demo.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final GoodsFundingService goodsFundingService;

    @Transactional
    public int notifyDeadlineSoon(Long goodsFundingId, List<Long> userIds) {
        return notify(goodsFundingId, userIds, NotificationType.DEADLINE_SOON,
                () -> goodsFundingService.findRecruiting(goodsFundingId));
    }

    @Transactional
    public int notifyFundingFailed(Long goodsFundingId, List<Long> userIds) {
        return notify(goodsFundingId, userIds, NotificationType.FUNDING_FAILED,
                () -> goodsFundingService.findFailed(goodsFundingId));
    }

    private int notify(Long goodsFundingId, List<Long> userIds, NotificationType type,
                        Supplier<Optional<GoodsFunding>> stateCheck) {
        GoodsFunding goodsFunding = stateCheck.get().orElse(null);
        if (goodsFunding == null) {
            return 0;
        }
        int sentCount = 0;
        for (Long userId : userIds) {
            if (notificationRepository.existsByUserIdAndGoodsFundingIdAndType(userId, goodsFundingId, type)) {
                continue;
            }
            try {
                Notification notification = Notification.builder()
                        .user(userRepository.getReferenceById(userId))
                        .goodsFunding(goodsFunding)
                        .type(type)
                        .build();
                notification.markSent(LocalDateTime.now());
                notificationRepository.save(notification);
                log.info("알림 발송: type={}, userId={}, goodsFundingId={}", type, userId, goodsFundingId);
                sentCount++;
            } catch (DataIntegrityViolationException e) {
                log.info("알림 중복 시도 무시: type={}, userId={}, goodsFundingId={}", type, userId, goodsFundingId);
            }
        }
        return sentCount;
    }
}
