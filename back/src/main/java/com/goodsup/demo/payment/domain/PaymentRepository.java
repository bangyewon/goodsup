package com.goodsup.demo.payment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    @Query("select p.id from Payment p where p.orders.goodsFunding.id = :goodsFundingId and p.status = :status")
    List<Long> findIdsByOrdersGoodsFundingIdAndStatus(
            @Param("goodsFundingId") Long goodsFundingId, @Param("status") PaymentStatus status);
}
