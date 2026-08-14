package com.goodsup.demo.goods.domain;

import com.goodsup.demo.common.domain.BaseEntity;
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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "goods_funding")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GoodsFunding extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id", nullable = false)
    private User host;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", nullable = false)
    private int price;

    @Column(name = "target_quantity", nullable = false)
    private int targetQuantity;

    @Column(name = "current_quantity", nullable = false)
    private int currentQuantity;

    @Column(name = "max_quantity_per_user", nullable = false)
    private int maxQuantityPerUser;

    @Column(name = "deadline_at", nullable = false)
    private LocalDateTime deadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private GoodsFundingStatus status;

    @Builder
    private GoodsFunding(User host, String title, String description, int price,
                          int targetQuantity, int maxQuantityPerUser, LocalDateTime deadlineAt) {
        this.host = host;
        this.title = title;
        this.description = description;
        this.price = price;
        this.targetQuantity = targetQuantity;
        this.maxQuantityPerUser = maxQuantityPerUser;
        this.deadlineAt = deadlineAt;
        this.currentQuantity = 0;
        this.status = GoodsFundingStatus.RECRUITING;
    }
}
