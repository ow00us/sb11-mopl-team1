package com.mopl.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.notification.dto.NotificationDto;
import com.mopl.notification.event.NotificationCreatedEvent;
import com.mopl.notification.entity.Notification;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.entity.NotificationType;
import com.mopl.notification.kafka.NotificationCreateCommand;
import com.mopl.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final UUID RECEIVER_ID = UUID.fromString(
        "11111111-1111-1111-1111-111111111111"
    );

    private static final UUID NOTIFICATION_ID = UUID.fromString(
        "22222222-2222-2222-2222-222222222222"
    );

    @Mock
    NotificationRepository notificationRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    NotificationService notificationService;

    @Test
    @DisplayName("알림을 생성하고 저장된 알림을 반환")
    void create_success() {
        // given
        Instant createdAt =
            Instant.parse("2026-07-28T01:00:00Z");

        when(notificationRepository.save(any(Notification.class)))
            .thenAnswer(invocation -> {
                Notification notification =
                    invocation.getArgument(0);

                ReflectionTestUtils.setField(
                    notification,
                    "id",
                    NOTIFICATION_ID
                );
                ReflectionTestUtils.setField(
                    notification,
                    "createdAt",
                    createdAt
                );

                return notification;
            });

        // when
        NotificationDto result = notificationService.create(
            RECEIVER_ID,
            null,
            "새로운 알림",
            "알림 내용",
            NotificationLevel.INFO
        );

        // then
        assertThat(result.id()).isEqualTo(NOTIFICATION_ID);
        assertThat(result.createdAt()).isEqualTo(createdAt);
        assertThat(result.receiverId()).isEqualTo(RECEIVER_ID);
        assertThat(result.title()).isEqualTo("새로운 알림");
        assertThat(result.content()).isEqualTo("알림 내용");
        assertThat(result.level())
            .isEqualTo(NotificationLevel.INFO);

        ArgumentCaptor<Notification> captor =
            ArgumentCaptor.forClass(Notification.class);

        verify(notificationRepository).save(captor.capture());

        assertThat(captor.getValue().getReceiverId())
            .isEqualTo(RECEIVER_ID);
        assertThat(captor.getValue().getReadAt()).isNull();

        ArgumentCaptor<NotificationCreatedEvent> eventCaptor =
            ArgumentCaptor.forClass(
                NotificationCreatedEvent.class
            );

        verify(eventPublisher).publishEvent(
            eventCaptor.capture()
        );

        NotificationDto publishedNotification =
            eventCaptor.getValue().notification();

        assertThat(publishedNotification.id())
            .isEqualTo(NOTIFICATION_ID);

        assertThat(publishedNotification.receiverId())
            .isEqualTo(RECEIVER_ID);
    }

    @Test
    @DisplayName("대상 정보를 포함한 알림을 생성하고 이벤트를 발행")
    void create_withTargetInformation_success() {
        // given
        UUID sourceEventId = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

        UUID resourceId = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

        UUID sourceEntityId = UUID.fromString(
            "55555555-5555-5555-5555-555555555555"
        );

        Instant createdAt =
            Instant.parse("2026-08-13T01:00:00Z");

        when(
            notificationRepository.save(
                any(Notification.class)
            )
        ).thenAnswer(invocation -> {
            Notification notification =
                invocation.getArgument(0);

            ReflectionTestUtils.setField(
                notification,
                "id",
                NOTIFICATION_ID
            );

            ReflectionTestUtils.setField(
                notification,
                "createdAt",
                createdAt
            );

            return notification;
        });

        // when
        NotificationDto result =
            notificationService.create(
                RECEIVER_ID,
                sourceEventId,
                NotificationType.DIRECT_MESSAGE,
                resourceId,
                sourceEntityId,
                "새로운 DM",
                "메시지가 도착했습니다.",
                NotificationLevel.INFO
            );

        // then
        assertThat(result.id())
            .isEqualTo(NOTIFICATION_ID);

        assertThat(result.type())
            .isEqualTo(
                NotificationType.DIRECT_MESSAGE
            );

        assertThat(result.resourceId())
            .isEqualTo(resourceId);

        ArgumentCaptor<Notification> notificationCaptor =
            ArgumentCaptor.forClass(
                Notification.class
            );

        verify(notificationRepository).save(
            notificationCaptor.capture()
        );

        Notification saved =
            notificationCaptor.getValue();

        assertThat(saved.getSourceEventId())
            .isEqualTo(sourceEventId);

        assertThat(saved.getType())
            .isEqualTo(
                NotificationType.DIRECT_MESSAGE
            );

        assertThat(saved.getResourceId())
            .isEqualTo(resourceId);

        assertThat(saved.getSourceEntityId())
            .isEqualTo(sourceEntityId);

        ArgumentCaptor<NotificationCreatedEvent> eventCaptor =
            ArgumentCaptor.forClass(
                NotificationCreatedEvent.class
            );

        verify(eventPublisher).publishEvent(
            eventCaptor.capture()
        );

        NotificationDto published =
            eventCaptor.getValue().notification();

        assertThat(published.type())
            .isEqualTo(
                NotificationType.DIRECT_MESSAGE
            );

        assertThat(published.resourceId())
            .isEqualTo(resourceId);
    }

    @Test
    @DisplayName("첫 페이지에서 미읽음 알림을 최신순으로 조회")
    void getUnreadNotifications_firstPage_success() {
        // given
        Notification first = createNotification(
            UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3"
            ),
            Instant.parse("2026-07-28T03:00:00Z"),
            "최신 알림"
        );

        Notification second = createNotification(
            UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
            ),
            Instant.parse("2026-07-28T02:00:00Z"),
            "중간 알림"
        );

        Notification third = createNotification(
            UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"
            ),
            Instant.parse("2026-07-28T01:00:00Z"),
            "오래된 알림"
        );

        when(
            notificationRepository
                .findByReceiverIdAndReadAtIsNull(
                    eq(RECEIVER_ID),
                    any(Pageable.class)
                )
        ).thenReturn(List.of(first, second, third));

        when(
            notificationRepository
                .countByReceiverIdAndReadAtIsNull(RECEIVER_ID)
        ).thenReturn(3L);

        // when
        CursorResponse<NotificationDto> result =
            notificationService.getUnreadNotifications(
                RECEIVER_ID,
                null,
                null,
                2,
                "DESCENDING",
                "createdAt"
            );

        // then
        assertThat(result.data())
            .extracting(NotificationDto::id)
            .containsExactly(
                first.getId(),
                second.getId()
            );

        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor())
            .isEqualTo(second.getCreatedAt().toString());
        assertThat(result.nextIdAfter())
            .isEqualTo(second.getId());
        assertThat(result.totalCount()).isEqualTo(3L);
        assertThat(result.sortBy()).isEqualTo("createdAt");
        assertThat(result.sortDirection())
            .isEqualTo("DESCENDING");
    }

    @Test
    @DisplayName("최신순 커서 이후의 미읽음 알림을 조회")
    void getUnreadNotifications_afterCursor_descending() {
        // given
        Instant cursor =
            Instant.parse("2026-07-28T03:00:00Z");
        UUID idAfter = UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
        );

        when(
            notificationRepository.findUnreadAfterDescending(
                eq(RECEIVER_ID),
                eq(cursor),
                eq(idAfter),
                any(Pageable.class)
            )
        ).thenReturn(List.of());

        when(
            notificationRepository
                .countByReceiverIdAndReadAtIsNull(RECEIVER_ID)
        ).thenReturn(0L);

        // when
        CursorResponse<NotificationDto> result =
            notificationService.getUnreadNotifications(
                RECEIVER_ID,
                cursor.toString(),
                idAfter,
                10,
                "DESCENDING",
                "createdAt"
            );

        // then
        assertThat(result.data()).isEmpty();
        assertThat(result.hasNext()).isFalse();

        verify(notificationRepository)
            .findUnreadAfterDescending(
                eq(RECEIVER_ID),
                eq(cursor),
                eq(idAfter),
                any(Pageable.class)
            );
    }

    @Test
    @DisplayName("오래된순 커서 이후의 미읽음 알림을 조회")
    void getUnreadNotifications_afterCursor_ascending() {
        // given
        Instant cursor =
            Instant.parse("2026-07-28T03:00:00Z");
        UUID idAfter = UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
        );

        when(
            notificationRepository.findUnreadAfterAscending(
                eq(RECEIVER_ID),
                eq(cursor),
                eq(idAfter),
                any(Pageable.class)
            )
        ).thenReturn(List.of());

        when(
            notificationRepository
                .countByReceiverIdAndReadAtIsNull(RECEIVER_ID)
        ).thenReturn(0L);

        // when
        CursorResponse<NotificationDto> result =
            notificationService.getUnreadNotifications(
                RECEIVER_ID,
                cursor.toString(),
                idAfter,
                10,
                "ASCENDING",
                "createdAt"
            );

        // then
        assertThat(result.data()).isEmpty();
        assertThat(result.hasNext()).isFalse();

        verify(notificationRepository)
            .findUnreadAfterAscending(
                eq(RECEIVER_ID),
                eq(cursor),
                eq(idAfter),
                any(Pageable.class)
            );
    }

    @Test
    @DisplayName("읽지 않은 본인의 알림을 읽음 처리")
    void read_success() {
        // given
        when(
            notificationRepository.markAsReadIfUnread(
                eq(NOTIFICATION_ID),
                eq(RECEIVER_ID),
                any(Instant.class)
            )
        ).thenReturn(1);

        // when
        notificationService.read(
            NOTIFICATION_ID,
            RECEIVER_ID
        );

        // then
        verify(notificationRepository)
            .markAsReadIfUnread(
                eq(NOTIFICATION_ID),
                eq(RECEIVER_ID),
                any(Instant.class)
            );
    }

    @Test
    @DisplayName("이미 읽은 본인 알림을 다시 읽음 처리하면 성공")
    void read_alreadyRead_success() {
        // given
        Notification notification = Notification.create(
            RECEIVER_ID,
            null,
            "새로운 알림",
            "알림 내용",
            NotificationLevel.INFO
        );

        Instant firstReadAt =
            Instant.parse("2026-08-13T01:00:00Z");

        notification.markAsRead(firstReadAt);

        when(
            notificationRepository.markAsReadIfUnread(
                eq(NOTIFICATION_ID),
                eq(RECEIVER_ID),
                any(Instant.class)
            )
        ).thenReturn(0);

        when(
            notificationRepository.findByIdAndReceiverId(
                NOTIFICATION_ID,
                RECEIVER_ID
            )
        ).thenReturn(
            Optional.of(notification)
        );

        // when
        notificationService.read(
            NOTIFICATION_ID,
            RECEIVER_ID
        );

        // then
        assertThat(notification.getReadAt())
            .isEqualTo(firstReadAt);

        verify(notificationRepository)
            .findByIdAndReceiverId(
                NOTIFICATION_ID,
                RECEIVER_ID
            );
    }

    @Test
    @DisplayName("본인이 소유한 알림이 없으면 읽음 처리에 실패")
    void read_notificationNotFound_throwsException() {
        // given
        when(
            notificationRepository.markAsReadIfUnread(
                eq(NOTIFICATION_ID),
                eq(RECEIVER_ID),
                any(Instant.class)
            )
        ).thenReturn(0);

        when(
            notificationRepository.findByIdAndReceiverId(
                NOTIFICATION_ID,
                RECEIVER_ID
            )
        ).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            notificationService.read(
                NOTIFICATION_ID,
                RECEIVER_ID
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("지원하지 않는 정렬 방향이면 알림 조회에 실패")
    void getUnreadNotifications_invalidSortDirection_throwsException() {
        // when & then
        assertThatThrownBy(() ->
            notificationService.getUnreadNotifications(
                RECEIVER_ID,
                null,
                null,
                10,
                "INVALID",
                "createdAt"
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(notificationRepository);
    }

    private Notification createNotification(
        UUID notificationId,
        Instant createdAt,
        String title
    ) {
        Notification notification = Notification.create(
            RECEIVER_ID,
            null,
            title,
            title + " 내용",
            NotificationLevel.INFO
        );

        ReflectionTestUtils.setField(
            notification,
            "id",
            notificationId
        );
        ReflectionTestUtils.setField(
            notification,
            "createdAt",
            createdAt
        );

        return notification;
    }

    @Test
    @DisplayName("limit이 100보다 크면 알림 조회에 실패")
    void getUnreadNotifications_limitTooLarge_throwsException() {
        // when & then
        assertThatThrownBy(() ->
            notificationService.getUnreadNotifications(
                RECEIVER_ID,
                null,
                null,
                101,
                "DESCENDING",
                "createdAt"
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(notificationRepository);
    }

    @Test
    @DisplayName("Kafka 알림을 처음 소비하면 저장하고 이벤트를 발행")
    void createIfAbsent_newEvent_savesAndPublishes() {
        // given
        UUID sourceEventId = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

        UUID resourceId = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

        UUID sourceEntityId = UUID.fromString(
            "55555555-5555-5555-5555-555555555555"
        );

        NotificationCreateCommand command =
            createCommand(
                sourceEventId,
                resourceId,
                sourceEntityId
            );

        when(
            notificationRepository.insertIfAbsent(
                any(UUID.class),
                any(Instant.class),
                eq(RECEIVER_ID),
                eq(sourceEventId),
                eq("DIRECT_MESSAGE"),
                eq(resourceId),
                eq(sourceEntityId),
                eq("[DM] 발신자"),
                eq("안녕하세요"),
                eq("INFO")
            )
        ).thenReturn(1);

        Notification saved = Notification.create(
            RECEIVER_ID,
            sourceEventId,
            NotificationType.DIRECT_MESSAGE,
            resourceId,
            sourceEntityId,
            "[DM] 발신자",
            "안녕하세요",
            NotificationLevel.INFO
        );

        ReflectionTestUtils.setField(
            saved,
            "id",
            NOTIFICATION_ID
        );

        ReflectionTestUtils.setField(
            saved,
            "createdAt",
            Instant.parse("2026-08-14T01:00:00Z")
        );

        when(
            notificationRepository.findById(
                any(UUID.class)
            )
        ).thenReturn(Optional.of(saved));

        // when
        boolean result =
            notificationService.createIfAbsent(command);

        // then
        assertThat(result).isTrue();

        verify(notificationRepository)
            .findById(any(UUID.class));

        ArgumentCaptor<NotificationCreatedEvent> eventCaptor =
            ArgumentCaptor.forClass(
                NotificationCreatedEvent.class
            );

        verify(eventPublisher).publishEvent(
            eventCaptor.capture()
        );

        NotificationDto published =
            eventCaptor.getValue().notification();

        assertThat(published.id())
            .isEqualTo(NOTIFICATION_ID);

        assertThat(published.type())
            .isEqualTo(NotificationType.DIRECT_MESSAGE);

        assertThat(published.resourceId())
            .isEqualTo(resourceId);
    }

    @Test
    @DisplayName("같은 Kafka 알림을 다시 소비하면 저장과 이벤트 발행을 생략")
    void createIfAbsent_duplicateEvent_skips() {
        // given
        NotificationCreateCommand command =
            createCommand(
                UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
                ),
                UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
                ),
                UUID.fromString(
                    "55555555-5555-5555-5555-555555555555"
                )
            );

        when(
            notificationRepository.insertIfAbsent(
                any(UUID.class),
                any(Instant.class),
                eq(command.receiverId()),
                eq(command.sourceEventId()),
                eq("DIRECT_MESSAGE"),
                eq(command.resourceId()),
                eq(command.sourceEntityId()),
                eq(command.title()),
                eq(command.content()),
                eq("INFO")
            )
        ).thenReturn(0);

        // when
        boolean result =
            notificationService.createIfAbsent(command);

        // then
        assertThat(result).isFalse();

        verify(notificationRepository, never())
            .findById(any(UUID.class));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("Kafka 알림 저장에 실패하면 예외를 전파하고 이벤트를 발행하지 않음")
    void createIfAbsent_saveFailure_propagatesException() {
        // given
        NotificationCreateCommand command =
            createCommand(
                UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
                ),
                UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
                ),
                UUID.fromString(
                    "55555555-5555-5555-5555-555555555555"
                )
            );

        when(
            notificationRepository.insertIfAbsent(
                any(UUID.class),
                any(Instant.class),
                eq(command.receiverId()),
                eq(command.sourceEventId()),
                eq("DIRECT_MESSAGE"),
                eq(command.resourceId()),
                eq(command.sourceEntityId()),
                eq(command.title()),
                eq(command.content()),
                eq("INFO")
            )
        ).thenThrow(
            new IllegalStateException(
                "일시적인 DB 오류입니다."
            )
        );

        // when & then
        assertThatThrownBy(() ->
            notificationService.createIfAbsent(command)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("일시적인 DB 오류입니다.");

        verifyNoInteractions(eventPublisher);
    }

    private NotificationCreateCommand createCommand(
        UUID sourceEventId,
        UUID resourceId,
        UUID sourceEntityId
    ) {
        return new NotificationCreateCommand(
            RECEIVER_ID,
            sourceEventId,
            NotificationType.DIRECT_MESSAGE,
            resourceId,
            sourceEntityId,
            "[DM] 발신자",
            "안녕하세요",
            NotificationLevel.INFO
        );
    }
}
