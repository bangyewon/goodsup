package com.goodsup.demo.goods.service;

import com.goodsup.demo.notification.service.NotificationService;
import com.goodsup.demo.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadlineSettlementSchedulerTest {

    @Mock
    private GoodsFundingService goodsFundingService;

    @Mock
    private OrderService orderService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private DeadlineSettlementScheduler deadlineSettlementScheduler;

    @Test
    void 한_건이_락_경합으로_실패해도_나머지_후보는_계속_정산한다() {
        when(goodsFundingService.findExpiredRecruitingIds(any(LocalDateTime.class))).thenReturn(List.of(1L, 2L));
        when(goodsFundingService.settleAsFailed(eq(1L), any(LocalDateTime.class)))
                .thenThrow(new CannotAcquireLockException("lock timeout"));
        when(goodsFundingService.settleAsFailed(eq(2L), any(LocalDateTime.class))).thenReturn(true);
        when(orderService.findParticipantUserIds(2L)).thenReturn(List.of(10L, 20L));

        deadlineSettlementScheduler.run();

        verify(notificationService, never()).notifyFundingFailed(eq(1L), anyList());
        verify(notificationService).notifyFundingFailed(2L, List.of(10L, 20L));
    }

    @Test
    void 목표를_채워_전이가_일어나지_않으면_알림을_보내지_않는다() {
        when(goodsFundingService.findExpiredRecruitingIds(any(LocalDateTime.class))).thenReturn(List.of(1L));
        when(goodsFundingService.settleAsFailed(eq(1L), any(LocalDateTime.class))).thenReturn(false);

        deadlineSettlementScheduler.run();

        verify(orderService, never()).findParticipantUserIds(anyLong());
        verify(notificationService, never()).notifyFundingFailed(anyLong(), anyList());
    }
}
