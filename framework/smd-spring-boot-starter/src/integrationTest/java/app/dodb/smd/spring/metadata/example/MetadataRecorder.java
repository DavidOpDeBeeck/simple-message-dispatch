package app.dodb.smd.spring.metadata.example;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@Component
public class MetadataRecorder {

    private final CountDownLatch nestedEventRecorded = new CountDownLatch(1);
    private final List<RecordedMetadata> commands = new CopyOnWriteArrayList<>();
    private final List<RecordedMetadata> queries = new CopyOnWriteArrayList<>();
    private final List<RecordedEventMetadata> accountCreatedEvents = new CopyOnWriteArrayList<>();
    private final List<RecordedMetadata> nestedQueries = new CopyOnWriteArrayList<>();
    private final List<RecordedMetadata> nestedEvents = new CopyOnWriteArrayList<>();

    public void recordCommand(Metadata metadata, String value) {
        commands.add(new RecordedMetadata(metadata, value));
    }

    public void recordQuery(Metadata metadata, String value) {
        queries.add(new RecordedMetadata(metadata, value));
    }

    public void recordAccountCreatedEvent(Metadata metadata, MessageId messageId, String value) {
        accountCreatedEvents.add(new RecordedEventMetadata(messageId, metadata, value));
    }

    public void recordNestedQuery(Metadata metadata, String value) {
        nestedQueries.add(new RecordedMetadata(metadata, value));
    }

    public void recordNestedEvent(Metadata metadata, String value) {
        nestedEvents.add(new RecordedMetadata(metadata, value));
        nestedEventRecorded.countDown();
    }

    public void awaitNestedEvent() throws InterruptedException {
        assertThat(nestedEventRecorded.await(5, SECONDS)).as("nested event recorded").isTrue();
    }

    public List<RecordedMetadata> commands() {
        return List.copyOf(commands);
    }

    public List<RecordedMetadata> queries() {
        return List.copyOf(queries);
    }

    public List<RecordedEventMetadata> accountCreatedEvents() {
        return List.copyOf(accountCreatedEvents);
    }

    public List<RecordedMetadata> nestedQueries() {
        return List.copyOf(nestedQueries);
    }

    public List<RecordedMetadata> nestedEvents() {
        return List.copyOf(nestedEvents);
    }

    public record RecordedMetadata(Metadata metadata, String value) {

        public RecordedMetadata {
            requireNonNull(metadata);
            requireNonNull(value);
        }
    }

    public record RecordedEventMetadata(MessageId messageId, Metadata metadata, String value) {

        public RecordedEventMetadata {
            requireNonNull(messageId);
            requireNonNull(metadata);
            requireNonNull(value);
        }
    }
}
