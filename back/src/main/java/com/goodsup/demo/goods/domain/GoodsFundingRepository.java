package com.goodsup.demo.goods.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GoodsFundingRepository extends JpaRepository<GoodsFunding, Long> {
}
