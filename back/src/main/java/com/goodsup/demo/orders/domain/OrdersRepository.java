package com.goodsup.demo.orders.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OrdersRepository extends JpaRepository<Orders, Long> {

    @Query("select coalesce(sum(o.quantity), 0) from Orders o "
            + "where o.goodsFunding.id = :goodsFundingId and o.user.id = :userId")
    int sumQuantityByGoodsFundingIdAndUserId(@Param("goodsFundingId") Long goodsFundingId, @Param("userId") Long userId);
}
