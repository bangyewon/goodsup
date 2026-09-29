package com.goodsup.demo.payment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboxEvent o where o.id = :id")
    Optional<OutboxEvent> findByIdForUpdate(@Param("id") Long id);

    @Query("select o.id from OutboxEvent o where "
            + "(o.status = com.goodsup.demo.payment.domain.OutboxEventStatus.PENDING and o.nextAttemptAt <= :now) "
            + "or (o.status = com.goodsup.demo.payment.domain.OutboxEventStatus.PROCESSING and o.claimedAt <= :staleBefore) "
            + "order by o.nextAttemptAt asc")
    List<Long> findClaimableCandidateIds(@Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore);
}
