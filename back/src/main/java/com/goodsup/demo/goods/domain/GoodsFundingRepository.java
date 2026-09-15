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

    // ADR-0002 실험 후보 A-1/A-3: 락 없이 정산 대상 후보 id만 조회 (findByIdForUpdate로 개별 재검증)
    @Query("select gf.id from GoodsFunding gf where gf.status = :status and gf.deadlineAt <= :referenceTime")
    List<Long> findIdsByStatusAndDeadlineAtBefore(
            @Param("status") GoodsFundingStatus status,
            @Param("referenceTime") LocalDateTime referenceTime);

    // ADR-0002 실험 후보 A-2: 조건부 벌크 UPDATE. 실제 전이된 대상은 findAllByIdInAndStatus로 재조회해야 식별 가능.
    @Modifying(clearAutomatically = true)
    @Query("update GoodsFunding gf set gf.status = com.goodsup.demo.goods.domain.GoodsFundingStatus.FAILED "
            + "where gf.id in :ids and gf.status = com.goodsup.demo.goods.domain.GoodsFundingStatus.RECRUITING "
            + "and gf.deadlineAt <= :referenceTime and gf.currentQuantity < gf.targetQuantity")
    int bulkCloseAsFailed(@Param("ids") List<Long> ids, @Param("referenceTime") LocalDateTime referenceTime);

    List<GoodsFunding> findAllByIdInAndStatus(List<Long> ids, GoodsFundingStatus status);
}
