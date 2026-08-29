package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.support.KafkaHeaders;

@ExtendWith(MockitoExtension.class)
class DeadLetterInspectorTest {

    private static final String TOPIC = MoplTopics.FOLLOW_EVENTS;
    private static final String DLT = MoplTopics.deadLetterTopicOf(TOPIC);
    private static final TopicPartition PARTITION = new TopicPartition(DLT, 0);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    @Mock
    private Consumer<String, byte[]> consumer;

    private final MutableClock clock = new MutableClock();
    private final AtomicInteger createdConsumers = new AtomicInteger();
    private DeadLetterInspector inspector;

    @BeforeEach
    void setUp() {
        inspector = new DeadLetterInspector(new ObjectMapper(), () -> {
            createdConsumers.incrementAndGet();
            return consumer;
        }, clock);
    }

    @AfterEach
    void lookupNeverCommitsOffsets() {
        assertThat(mockingDetails(consumer).getInvocations())
            .extracting(invocation -> invocation.getMethod().getName())
            .doesNotContain("commitSync", "commitAsync");
    }

    @Test
    @DisplayName("Spring은 운영 생성자로 주입하며 실제 조회 전에는 Kafka에 연결하지 않는다")
    void springWiring_keepsConsumerCreationLazy() {
        KafkaConnectionDetails connectionDetails = mock(KafkaConnectionDetails.class);

        new ApplicationContextRunner()
            .withBean(KafkaProperties.class, KafkaProperties::new)
            .withBean(KafkaConnectionDetails.class, () -> connectionDetails)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(DeadLetterInspector.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(DeadLetterInspector.class);
                verifyNoInteractions(connectionDetails);
            });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -10})
    @DisplayName("0 또는 음수 limit은 Consumer를 만들기 전에 거부한다")
    void find_rejectsNonPositiveLimit(int limit) {
        assertThatThrownBy(() -> inspector.find(DLT, limit))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit");
        assertThat(createdConsumers).hasValue(0);
        verifyNoInteractions(consumer);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"mopl.follow.events", "unknown.DLT"})
    @DisplayName("계약 밖 DLT는 목록과 좌표 조회 모두 연결 전에 거부한다")
    void lookup_rejectsUnknownTopic(String topic) {
        assertThatThrownBy(() -> inspector.find(topic, 1))
            .isInstanceOf(EventContractViolationException.class);
        assertThatThrownBy(() -> inspector.findRawAt(topic, 0, 0))
            .isInstanceOf(EventContractViolationException.class);
        assertThat(createdConsumers).hasValue(0);
        verifyNoInteractions(consumer);
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @DisplayName("파티션 정보가 없으면 빈 결과를 반환하고 Consumer를 닫는다")
    void find_noPartitions(List<PartitionInfo> partitions) {
        when(consumer.partitionsFor(DLT)).thenReturn(partitions);

        assertThat(inspector.find(DLT, 10)).isEmpty();

        verify(consumer, never()).assign(anyCollection());
        verify(consumer, never()).poll(POLL_TIMEOUT);
        verify(consumer).close();
    }

    @Test
    @DisplayName("한 poll에 limit보다 많은 레코드가 있어도 요청한 개수까지만 반환한다")
    void find_stopsAtLimit() {
        givenListing(4L);
        when(consumer.position(PARTITION)).thenReturn(0L);
        when(consumer.poll(POLL_TIMEOUT)).thenReturn(records(
            record(0, "{}"), record(1, null), record(2, "broken"), record(3, "{}")))
            .thenThrow(new AssertionError("limit 도달 후에는 다시 poll하면 안 됩니다."));

        assertThat(inspector.find(DLT, 2))
            .extracting(DeadLetterRecord::offset)
            .containsExactly(0L, 1L);

        verify(consumer, times(1)).poll(POLL_TIMEOUT);
        verify(consumer).seekToBeginning(List.of(PARTITION));
        verify(consumer).close();
    }

    @Test
    @DisplayName("레코드가 limit보다 적으면 end offset에서 종료하며 깨진 값도 결과에 남긴다")
    void find_stopsWhenOffsetsAreExhausted() {
        givenListing(2L);
        when(consumer.position(PARTITION)).thenReturn(0L, 2L);
        when(consumer.poll(POLL_TIMEOUT)).thenReturn(records(record(0, "broken"), record(1, null)));

        List<DeadLetterRecord> found = inspector.find(DLT, 10);

        assertThat(found).extracting(DeadLetterRecord::offset).containsExactly(0L, 1L);
        assertThat(found).extracting(DeadLetterRecord::eventId).containsOnlyNulls();
        verify(consumer, times(1)).poll(POLL_TIMEOUT);
        verify(consumer).close();
    }

    @Test
    @DisplayName("처음부터 읽을 offset이 없으면 poll하지 않는다")
    void find_emptyPartitionDoesNotPoll() {
        givenListing(0L);
        when(consumer.position(PARTITION)).thenReturn(0L);

        assertThat(inspector.find(DLT, 10)).isEmpty();

        verify(consumer, never()).poll(POLL_TIMEOUT);
        verify(consumer).close();
    }

    @Test
    @DisplayName("앞선 파티션이 비어 있어도 다른 파티션에 남은 레코드를 읽는다")
    void find_readsRemainingPartition() {
        TopicPartition second = new TopicPartition(DLT, 1);
        when(consumer.partitionsFor(DLT)).thenReturn(List.of(partitionInfo(0), partitionInfo(1)));
        Map<TopicPartition, Long> endOffsets = new LinkedHashMap<>();
        endOffsets.put(PARTITION, 0L);
        endOffsets.put(second, 5L);
        when(consumer.endOffsets(List.of(PARTITION, second)))
            .thenReturn(endOffsets);
        when(consumer.position(PARTITION)).thenReturn(0L);
        when(consumer.position(second)).thenReturn(4L, 5L);
        ConsumerRecord<String, byte[]> last = new ConsumerRecord<>(DLT, 1, 4L, "key", null);
        when(consumer.poll(POLL_TIMEOUT))
            .thenReturn(new ConsumerRecords<>(Map.of(second, List.of(last))));

        assertThat(inspector.find(DLT, 10))
            .extracting(DeadLetterRecord::partition).containsExactly(1);

        verify(consumer).close();
    }

    @Test
    @DisplayName("빈 poll 한 번만으로 종료하지 않고 다음 poll의 데이터를 읽는다")
    void find_retriesEmptyPollWhileOffsetsRemain() {
        givenListing(1L);
        when(consumer.position(PARTITION)).thenReturn(0L, 0L, 1L);
        when(consumer.poll(POLL_TIMEOUT))
            .thenReturn(ConsumerRecords.empty(), records(record(0, "{}")));

        assertThat(inspector.find(DLT, 10))
            .extracting(DeadLetterRecord::offset).containsExactly(0L);

        verify(consumer, times(2)).poll(POLL_TIMEOUT);
        verify(consumer).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("15초 경계에 도달하면 남은 offset과 무관하게 지금까지의 결과로 종료한다")
    void find_stopsExactlyAtDeadline(boolean hasRecord) {
        givenListing(10L);
        when(consumer.position(PARTITION)).thenReturn(0L);
        when(consumer.poll(POLL_TIMEOUT)).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(15));
            return hasRecord ? records(record(0, "{}")) : ConsumerRecords.empty();
        }).thenThrow(new AssertionError("deadline 이후에는 다시 poll하면 안 됩니다."));

        assertThat(inspector.find(DLT, 10)).hasSize(hasRecord ? 1 : 0);

        verify(consumer, times(1)).poll(POLL_TIMEOUT);
        verify(consumer).close();
    }

    @Test
    @DisplayName("조회 도중 Kafka 예외가 발생해도 Consumer를 닫는다")
    void find_closesConsumerWhenPollFails() {
        givenListing(1L);
        when(consumer.position(PARTITION)).thenReturn(0L);
        TimeoutException failure = new TimeoutException("poll failed");
        when(consumer.poll(POLL_TIMEOUT)).thenThrow(failure);

        assertThatThrownBy(() -> inspector.find(DLT, 10)).isSameAs(failure);

        verify(consumer).close();
    }

    @Test
    @DisplayName("음수 offset은 Consumer를 만들기 전에 거부한다")
    void findRawAt_rejectsNegativeOffset() {
        assertThatThrownBy(() -> inspector.findRawAt(DLT, 0, -1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("offset");
        assertThat(createdConsumers).hasValue(0);
        verifyNoInteractions(consumer);
    }

    @ParameterizedTest
    @ValueSource(longs = {2L, 5L, 6L})
    @DisplayName("보존 시작 이전과 end offset 이상은 다른 좌표로 이동하지 않고 부재를 반환한다")
    void findRawAt_rejectsOutOfRangeOffset(long offset) {
        givenRawOffsets(3L, 5L);

        assertThat(inspector.findRawAt(DLT, 0, offset)).isEmpty();

        verify(consumer, never()).seek(eq(PARTITION), anyLong());
        verify(consumer, never()).poll(POLL_TIMEOUT);
        verify(consumer).close();
    }

    @Test
    @DisplayName("offset 메타데이터가 없으면 빈 파티션으로 처리한다")
    void findRawAt_missingOffsetsAreEmpty() {
        when(consumer.endOffsets(List.of(PARTITION))).thenReturn(Map.of());
        when(consumer.beginningOffsets(List.of(PARTITION))).thenReturn(Map.of());

        assertThat(inspector.findRawAt(DLT, 0, 0)).isEmpty();

        verify(consumer, never()).poll(POLL_TIMEOUT);
        verify(consumer).close();
    }

    @Test
    @DisplayName("beginning offset의 레코드도 원본 바이트와 헤더를 유지한 채 반환한다")
    void findRawAt_returnsExactRecord() {
        givenRawOffsets(3L, 5L);
        ConsumerRecord<String, byte[]> expected = record(3L, "not-json");
        expected.headers().add("original", new byte[] {1, 2, 3});
        when(consumer.poll(POLL_TIMEOUT)).thenReturn(records(expected, record(4L, "{}")));

        assertThat(inspector.findRawAt(DLT, 0, 3L)).containsSame(expected);

        verify(consumer).seek(PARTITION, 3L);
        verify(consumer, times(1)).poll(POLL_TIMEOUT);
        verify(consumer).close();
    }

    @Test
    @DisplayName("목표 offset이 압축으로 비어 있으면 이후 레코드를 대신 반환하지 않는다")
    void findRawAt_doesNotSubstituteCompactedRecord() {
        givenRawOffsets(0L, 5L);
        when(consumer.poll(POLL_TIMEOUT)).thenReturn(records(record(4L, "{}")));

        assertThat(inspector.findRawAt(DLT, 0, 3L)).isEmpty();

        verify(consumer, times(1)).poll(POLL_TIMEOUT);
        verify(consumer).close();
    }

    @Test
    @DisplayName("좌표 조회는 빈 poll 후에도 deadline 전이면 정확한 레코드를 기다린다")
    void findRawAt_retriesEmptyPoll() {
        givenRawOffsets(0L, 5L);
        ConsumerRecord<String, byte[]> expected = record(3L, "{}");
        when(consumer.poll(POLL_TIMEOUT)).thenReturn(ConsumerRecords.empty(), records(expected));

        assertThat(inspector.findRawAt(DLT, 0, 3L)).containsSame(expected);

        verify(consumer, times(2)).poll(POLL_TIMEOUT);
        verify(consumer).close();
    }

    @Test
    @DisplayName("좌표 조회도 빈 poll이 계속되면 15초 경계에서 종료한다")
    void findRawAt_stopsExactlyAtDeadline() {
        givenRawOffsets(0L, 5L);
        when(consumer.poll(POLL_TIMEOUT)).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(15));
            return ConsumerRecords.empty();
        }).thenThrow(new AssertionError("deadline 이후에는 다시 poll하면 안 됩니다."));

        assertThat(inspector.findRawAt(DLT, 0, 3L)).isEmpty();

        verify(consumer, times(1)).poll(POLL_TIMEOUT);
        verify(consumer).close();
    }

    @Test
    @DisplayName("좌표 조회의 poll 실패에서도 Consumer를 닫고 원래 예외를 보존한다")
    void findRawAt_closesConsumerWhenPollFails() {
        givenRawOffsets(0L, 5L);
        TimeoutException failure = new TimeoutException("poll failed");
        when(consumer.poll(POLL_TIMEOUT)).thenThrow(failure);

        assertThatThrownBy(() -> inspector.findRawAt(DLT, 0, 3L)).isSameAs(failure);

        verify(consumer).close();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"{}", "{\"eventId\":null}", "{\"eventId\":7}",
        "{\"eventId\":{}}", "{\"eventId\":\"not-a-uuid\"}", "broken", "null", ""})
    @DisplayName("eventId를 읽을 수 없어도 레코드 좌표와 원본 토픽은 조회할 수 있다")
    void toDeadLetterRecord_toleratesUnreadableEventId(String value) {
        DeadLetterRecord result = inspector.toDeadLetterRecord(record(7L, value));

        assertThat(result.eventId()).isNull();
        assertThat(result.offset()).isEqualTo(7L);
        assertThat(result.originalTopic()).isEqualTo(TOPIC);
        assertThat(result.exceptionType()).isNull();
        assertThat(result.exceptionMessage()).isNull();
    }

    @Test
    @DisplayName("정상 eventId와 마지막 DLT 헤더를 사용하고 레코드 메타데이터를 보존한다")
    void toDeadLetterRecord_preservesMetadataAndLastHeaders() {
        UUID eventId = UUID.fromString("bb047670-b8f0-49ed-b529-2e8c3588ec5e");
        ConsumerRecord<String, byte[]> record = record(9L, "{\"eventId\":\"" + eventId + "\"}");
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, bytes(TOPIC));
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, bytes(MoplTopics.PLAYLIST_EVENTS));
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_FQCN, bytes("example.InvalidEvent"));
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, bytes("이벤트 형식 오류"));

        DeadLetterRecord result = inspector.toDeadLetterRecord(record);

        assertThat(result).isEqualTo(new DeadLetterRecord(
            DLT, 0, 9L, Instant.ofEpochMilli(record.timestamp()), MoplTopics.PLAYLIST_EVENTS,
            "key", eventId, "example.InvalidEvent", "이벤트 형식 오류"));
    }

    private void givenListing(long endOffset) {
        when(consumer.partitionsFor(DLT)).thenReturn(List.of(partitionInfo(0)));
        when(consumer.endOffsets(List.of(PARTITION))).thenReturn(Map.of(PARTITION, endOffset));
    }

    private void givenRawOffsets(long beginning, long end) {
        when(consumer.endOffsets(List.of(PARTITION))).thenReturn(Map.of(PARTITION, end));
        when(consumer.beginningOffsets(List.of(PARTITION))).thenReturn(Map.of(PARTITION, beginning));
    }

    private static PartitionInfo partitionInfo(int partition) {
        return new PartitionInfo(DLT, partition, null, new Node[0], new Node[0]);
    }

    private static ConsumerRecord<String, byte[]> record(long offset, String value) {
        return new ConsumerRecord<>(DLT, 0, offset, "key", value == null ? null : bytes(value));
    }

    @SafeVarargs
    private static ConsumerRecords<String, byte[]> records(ConsumerRecord<String, byte[]>... records) {
        return new ConsumerRecords<>(Map.of(PARTITION, List.of(records)));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-08-29T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
