package com.goodsup.demo.notification.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByUserIdAndGoodsFundingIdAndType(Long userId, Long goodsFundingId, NotificationType type);

    long countByGoodsFundingIdAndType(Long goodsFundingId, NotificationType type);
}
