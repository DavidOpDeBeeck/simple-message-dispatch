package app.dodb.smd.spring.metadata.example;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class MetadataRecorder {

    private final List<Metadata> commandMetadata = new CopyOnWriteArrayList<>();
    private final List<String> commandMetadataValues = new CopyOnWriteArrayList<>();
    private final List<Metadata> queryMetadata = new CopyOnWriteArrayList<>();
    private final List<String> queryMetadataValues = new CopyOnWriteArrayList<>();
    private final List<Metadata> accountCreatedEventMetadata = new CopyOnWriteArrayList<>();
    private final List<MessageId> accountCreatedEventMessageIds = new CopyOnWriteArrayList<>();
    private final List<String> accountCreatedEventValues = new CopyOnWriteArrayList<>();
    private final List<Metadata> nestedQueryMetadata = new CopyOnWriteArrayList<>();
    private final List<String> nestedQueryMetadataValues = new CopyOnWriteArrayList<>();
    private final List<Metadata> nestedEventMetadata = new CopyOnWriteArrayList<>();
    private final List<String> nestedEventMetadataValues = new CopyOnWriteArrayList<>();

    public void recordCommand(Metadata metadata, String value) {
        commandMetadata.add(metadata);
        commandMetadataValues.add(value);
    }

    public void recordQuery(Metadata metadata, String value) {
        queryMetadata.add(metadata);
        queryMetadataValues.add(value);
    }

    public void recordAccountCreatedEvent(Metadata metadata, MessageId messageId, String value) {
        accountCreatedEventMetadata.add(metadata);
        accountCreatedEventMessageIds.add(messageId);
        accountCreatedEventValues.add(value);
    }

    public void recordNestedQuery(Metadata metadata, String value) {
        nestedQueryMetadata.add(metadata);
        nestedQueryMetadataValues.add(value);
    }

    public void recordNestedEvent(Metadata metadata, String value) {
        nestedEventMetadata.add(metadata);
        nestedEventMetadataValues.add(value);
    }

    public List<Metadata> commandMetadata() {
        return commandMetadata;
    }

    public List<String> commandMetadataValues() {
        return commandMetadataValues;
    }

    public List<Metadata> queryMetadata() {
        return queryMetadata;
    }

    public List<String> queryMetadataValues() {
        return queryMetadataValues;
    }

    public List<Metadata> accountCreatedEventMetadata() {
        return accountCreatedEventMetadata;
    }

    public List<MessageId> accountCreatedEventMessageIds() {
        return accountCreatedEventMessageIds;
    }

    public List<String> accountCreatedEventValues() {
        return accountCreatedEventValues;
    }

    public List<Metadata> nestedQueryMetadata() {
        return nestedQueryMetadata;
    }

    public List<String> nestedQueryMetadataValues() {
        return nestedQueryMetadataValues;
    }

    public List<Metadata> nestedEventMetadata() {
        return nestedEventMetadata;
    }

    public List<String> nestedEventMetadataValues() {
        return nestedEventMetadataValues;
    }
}
