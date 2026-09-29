package com.goodsup.demo.goods.service;

import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.goods.domain.GoodsFundingStatus;
import com.goodsup.demo.goods.dto.request.RegisterGoodsFundingRequest;
import com.goodsup.demo.goods.dto.response.ShowGoodsFundingListResponse;
import com.goodsup.demo.goods.dto.response.ShowGoodsFundingResponse;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GoodsFundingService {
    private final GoodsFundingRepository goodsFundingRepository;
    private final UserService userService;

    @Transactional
    public long registerGoodsFunding(Long userId, RegisterGoodsFundingRequest request) {
        User user = userService.getUser(userId);
        GoodsFunding goodsFunding = request.toEntity(user);
        goodsFundingRepository.save(goodsFunding);
        return goodsFunding.getId();
    }

    public GoodsFunding getForUpdate(Long goodsFundingId) {
        return goodsFundingRepository.findByIdForUpdate(goodsFundingId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public ShowGoodsFundingResponse showGoodsFunding(Long goodsId) {
        GoodsFunding goodsFunding = goodsFundingRepository.findById(goodsId).orElseThrow(
                () -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        return ShowGoodsFundingResponse.from(goodsFunding);
    }
    @Transactional(readOnly = true)
    public Page<ShowGoodsFundingListResponse> showGoodsFundingList(Pageable pageable) {
        return goodsFundingRepository.findAllWithHost(pageable)
                .map(ShowGoodsFundingListResponse::from);
    }

    @Transactional(readOnly = true)
    public List<Long> findDeadlineSoonFundingIds(LocalDateTime now, LocalDateTime threshold) {
        return goodsFundingRepository.findIdsByStatusAndDeadlineAtBetween(
                GoodsFundingStatus.RECRUITING, now, threshold);
    }

    // 발송 직전 상태 확인 용도
    @Transactional(readOnly = true)
    public Optional<GoodsFunding> findRecruiting(Long goodsFundingId) {
        return goodsFundingRepository.findById(goodsFundingId)
                .filter(goodsFunding -> goodsFunding.getStatus() == GoodsFundingStatus.RECRUITING);
    }

    @Transactional(readOnly = true)
    public List<Long> findExpiredRecruitingIds(LocalDateTime referenceTime) {
        return goodsFundingRepository.findIdsByStatusAndDeadlineAtBefore(
                GoodsFundingStatus.RECRUITING, referenceTime);
    }

    @Transactional
    public boolean settleAsFailed(Long goodsFundingId, LocalDateTime referenceTime) {
        GoodsFunding goodsFunding = goodsFundingRepository.findByIdForUpdate(goodsFundingId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
        return goodsFunding.closeAsFailedIfDeadlinePassed(referenceTime);
    }

    // 발송 직전 상태 확인 용도
    @Transactional(readOnly = true)
    public Optional<GoodsFunding> findFailed(Long goodsFundingId) {
        return goodsFundingRepository.findById(goodsFundingId)
                .filter(goodsFunding -> goodsFunding.getStatus() == GoodsFundingStatus.FAILED);
    }
}
