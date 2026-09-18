package com.goodsup.demo.goods.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GoodsFundingRepository extends JpaRepository<GoodsFunding, Long> {
    @Query("select gf from GoodsFunding gf join fetch gf.host")
    Page<GoodsFunding> findAllWithHost(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select gf from GoodsFunding gf where gf.id = :id")
    Optional<GoodsFunding> findByIdForUpdate(Long id);

    @Query("select gf.id from GoodsFunding gf where gf.status = :status and gf.deadlineAt <= :referenceTime")
    List<Long> findIdsByStatusAndDeadlineAtBefore(
            @Param("status") GoodsFundingStatus status,
            @Param("referenceTime") LocalDateTime referenceTime);

    @Query("select gf.id from GoodsFunding gf where gf.status = :status and gf.deadlineAt between :now and :threshold")
    List<Long> findIdsByStatusAndDeadlineAtBetween(
            @Param("status") GoodsFundingStatus status,
            @Param("now") LocalDateTime now,
            @Param("threshold") LocalDateTime threshold);

    @Modifying(clearAutomatically = true)
    @Query("update GoodsFunding gf set gf.status = com.goodsup.demo.goods.domain.GoodsFundingStatus.FAILED "
            + "where gf.id in :ids and gf.status = com.goodsup.demo.goods.domain.GoodsFundingStatus.RECRUITING "
            + "and gf.deadlineAt <= :referenceTime and gf.currentQuantity < gf.targetQuantity")
    int bulkCloseAsFailed(@Param("ids") List<Long> ids, @Param("referenceTime") LocalDateTime referenceTime);

    List<GoodsFunding> findAllByIdInAndStatus(List<Long> ids, GoodsFundingStatus status);
}
