package com.goodsup.demo.notification.service;

import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.service.GoodsFundingService;
import com.goodsup.demo.notification.domain.Notification;
import com.goodsup.demo.notification.domain.NotificationRepository;
import com.goodsup.demo.notification.domain.NotificationType;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserService userService;

    @Mock
    private GoodsFundingService goodsFundingService;

    @InjectMocks
    private NotificationService notificationService;

    private GoodsFunding goodsFunding() {
        return GoodsFunding.builder()
                .host(User.builder().email("host@test.com").password("password").nickname("host").build())
                .title("굿즈펀딩")
                .description("설명")
                .price(10000)
                .targetQuantity(10)
                .maxQuantityPerUser(5)
                .deadlineAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    @Test
    void 이미_알림이_존재하면_다시_발송하지_않는다() {
        when(goodsFundingService.findRecruiting(1L)).thenReturn(Optional.of(goodsFunding()));
        when(notificationRepository.existsByUserIdAndGoodsFundingIdAndType(1L, 1L, NotificationType.DEADLINE_SOON))
                .thenReturn(true);

        int sentCount = notificationService.notifyDeadlineSoon(1L, List.of(1L));

        assertThat(sentCount).isEqualTo(0);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void 신규_대상에게는_마감임박_알림을_발송하고_SENT로_확정한다() {
        when(goodsFundingService.findRecruiting(1L)).thenReturn(Optional.of(goodsFunding()));
        when(notificationRepository.existsByUserIdAndGoodsFundingIdAndType(1L, 1L, NotificationType.DEADLINE_SOON))
                .thenReturn(false);
        when(userService.getReference(1L)).thenReturn(
                User.builder().email("user@test.com").password("password").nickname("user").build());
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int sentCount = notificationService.notifyDeadlineSoon(1L, List.of(1L));

        assertThat(sentCount).isEqualTo(1);
        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    @Test
    void 동시_실행으로_유니크_제약에_걸리면_해당_유저만_건너뛰고_나머지는_계속_처리한다() {
        when(goodsFundingService.findRecruiting(1L)).thenReturn(Optional.of(goodsFunding()));
        when(notificationRepository.existsByUserIdAndGoodsFundingIdAndType(
                anyLong(), eq(1L), eq(NotificationType.DEADLINE_SOON)))
                .thenReturn(false);
        when(userService.getReference(anyLong())).thenReturn(
                User.builder().email("user@test.com").password("password").nickname("user").build());
        when(notificationRepository.save(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int sentCount = notificationService.notifyDeadlineSoon(1L, List.of(1L, 2L));

        assertThat(sentCount).isEqualTo(1);
        verify(notificationRepository, times(2)).save(any(Notification.class));
    }

    @Test
    void 배치_조회_이후_이미_RECRUITING이_아니게_된_공구에는_알림을_보내지_않는다() {
        // 적대적 검증(CLAUDE.md 절차)으로 발견: 스케줄러가 후보 id를 조회한 시점과 이 메서드가
        // 실행되는 시점 사이에 참여/정산으로 상태가 FINISHED/FAILED로 바뀔 수 있다. 이미 끝난
        // 공구에 "마감임박" 알림을 보내는 건 데이터 정합성 문제는 아니지만 잘못된 알림이므로,
        // 발송 직전 실제 상태를 다시 확인해야 한다(docs/experiments/adversarial-test-log.md 참고).
        when(goodsFundingService.findRecruiting(1L)).thenReturn(Optional.empty());

        int sentCount = notificationService.notifyDeadlineSoon(1L, List.of(1L));

        assertThat(sentCount).isEqualTo(0);
        verify(notificationRepository, never())
                .existsByUserIdAndGoodsFundingIdAndType(anyLong(), anyLong(), any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void 정산_전이_확인이_안_되면_실패_알림을_보내지_않는다() {
        when(goodsFundingService.findFailed(1L)).thenReturn(Optional.empty());

        int sentCount = notificationService.notifyFundingFailed(1L, List.of(1L));

        assertThat(sentCount).isEqualTo(0);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void 참여자에게_정산_실패_알림을_발송한다() {
        when(goodsFundingService.findFailed(1L)).thenReturn(Optional.of(goodsFunding()));
        when(notificationRepository.existsByUserIdAndGoodsFundingIdAndType(1L, 1L, NotificationType.FUNDING_FAILED))
                .thenReturn(false);
        when(userService.getReference(1L)).thenReturn(
                User.builder().email("user@test.com").password("password").nickname("user").build());
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int sentCount = notificationService.notifyFundingFailed(1L, List.of(1L));

        assertThat(sentCount).isEqualTo(1);
        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    @Test
    void 이미_실패_알림이_존재하면_다시_발송하지_않는다() {
        when(goodsFundingService.findFailed(1L)).thenReturn(Optional.of(goodsFunding()));
        when(notificationRepository.existsByUserIdAndGoodsFundingIdAndType(1L, 1L, NotificationType.FUNDING_FAILED))
                .thenReturn(true);

        int sentCount = notificationService.notifyFundingFailed(1L, List.of(1L));

        assertThat(sentCount).isEqualTo(0);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void 정산_실패_알림_동시_실행으로_유니크_제약에_걸리면_해당_유저만_건너뛴다() {
        when(goodsFundingService.findFailed(1L)).thenReturn(Optional.of(goodsFunding()));
        when(notificationRepository.existsByUserIdAndGoodsFundingIdAndType(
                anyLong(), eq(1L), eq(NotificationType.FUNDING_FAILED)))
                .thenReturn(false);
        when(userService.getReference(anyLong())).thenReturn(
                User.builder().email("user@test.com").password("password").nickname("user").build());
        when(notificationRepository.save(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int sentCount = notificationService.notifyFundingFailed(1L, List.of(1L, 2L));

        assertThat(sentCount).isEqualTo(1);
        verify(notificationRepository, times(2)).save(any(Notification.class));
    }
}
