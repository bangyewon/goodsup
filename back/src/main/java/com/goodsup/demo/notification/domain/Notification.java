package com.goodsup.demo.notification.domain;

import com.goodsup.demo.common.domain.BaseEntity;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "notification", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "goods_funding_id", "type"}, name = "uk_notification_user_funding_type")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goods_funding_id",nullable = false)
    private GoodsFunding goodsFunding;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NotificationStatus status;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Builder
    private Notification(User user, GoodsFunding goodsFunding, NotificationType type) {
        this.user = user;
        this.goodsFunding = goodsFunding;
        this.type = type;
        this.status = NotificationStatus.PENDING;
    }
}
