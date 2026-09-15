package com.goodsup.demo.goods.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GoodsFundingRepository extends JpaRepository<GoodsFunding, Long> {
    @Query("select gf from GoodsFunding gf join fetch gf.host")
    Page<GoodsFunding> findAllWithHost(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select gf from GoodsFunding gf where gf.id = :id")
    Optional<GoodsFunding> findByIdForUpdate(Long id);
}
