package com.goodsup.demo.order.domain;

import com.goodsup.demo.common.domain.BaseEntity;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "orders", indexes = @Index(columnList = "goods_funding_id, user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Orders extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goods_funding_id", nullable = false)
    private GoodsFunding goodsFunding;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false)
    private DeliveryStatus deliveryStatus;

    @Builder
    private Orders(GoodsFunding goodsFunding, User user, int quantity) {
        this.goodsFunding = goodsFunding;
        this.user = user;
        this.quantity = quantity;
        this.deliveryStatus = DeliveryStatus.WAITING;
    }
}
