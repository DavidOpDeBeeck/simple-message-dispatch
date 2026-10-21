package app.dodb.smd.jms;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.Message;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

final class JmsWireFormat {

    static final String WIRE_VERSION_PROPERTY = "smd_wire_version";
    static final String WIRE_VERSION = "1";
    static final String MESSAGE_ID_PROPERTY = "smd_message_id";
    static final String SUBJECT_ID_PROPERTY = "smd_subject_id";
    static final String METADATA_PROPERTY = "smd_metadata";
    static final String TIMESTAMP_PROPERTY = "smd_timestamp";
    static final String GROUP_ID_PROPERTY = "JMSXGroupID";
    static final String SUBJECTLESS_GROUP = "smd-subjectless";
    static final String DUPLICATE_ID_PROPERTY = "_AMQ_DUPL_ID";

    private static final List<String> TRACE_CONTEXT_PROPERTIES = List.of("traceparent", "tracestate");

    private JmsWireFormat() {
    }

    static BytesMessage toMessage(JMSContext context, SerializedEvent event, Map<String, String> metadataProperties) throws JMSException {
        var message = context.createBytesMessage();
        message.writeBytes(event.serializedPayload());
        message.setJMSType(event.eventType());
        message.setStringProperty(WIRE_VERSION_PROPERTY, WIRE_VERSION);
        message.setStringProperty(MESSAGE_ID_PROPERTY, event.messageId().value().toString());
        message.setStringProperty(DUPLICATE_ID_PROPERTY, event.messageId().value().toString());
        message.setStringProperty(GROUP_ID_PROPERTY, event.subjectId().orElse(SUBJECTLESS_GROUP));
        if (event.subjectId().isPresent()) {
            message.setStringProperty(SUBJECT_ID_PROPERTY, event.subjectId().get());
        }
        message.setStringProperty(METADATA_PROPERTY, new String(event.serializedMetadata(), UTF_8));
        message.setStringProperty(TIMESTAMP_PROPERTY, event.createdAt().toString());
        for (var traceContextProperty : TRACE_CONTEXT_PROPERTIES) {
            var value = metadataProperties.get(traceContextProperty);
            if (value != null) {
                message.setStringProperty(traceContextProperty, value);
            }
        }
        return message;
    }

    static SerializedEvent fromMessage(Message message) throws JMSException {
        var wireVersion = message.getStringProperty(WIRE_VERSION_PROPERTY);
        if (!WIRE_VERSION.equals(wireVersion)) {
            throw new JmsWireFormatException("Unsupported SMD wire version: expected '" + WIRE_VERSION + "' in " + WIRE_VERSION_PROPERTY
                + ", got '" + wireVersion + "' (JMSMessageID=" + message.getJMSMessageID() + ")");
        }
        if (!(message instanceof BytesMessage bytesMessage)) {
            throw new JmsWireFormatException("SMD events must be JMS BytesMessages; got " + message.getClass().getName()
                + " (JMSMessageID=" + message.getJMSMessageID() + ")");
        }

        var messageId = requiredProperty(message, MESSAGE_ID_PROPERTY);
        var payload = new byte[(int) bytesMessage.getBodyLength()];
        bytesMessage.readBytes(payload);
        try {
            return new SerializedEvent(
                new MessageId(UUID.fromString(messageId)),
                null,
                Optional.ofNullable(message.getStringProperty(SUBJECT_ID_PROPERTY)),
                requiredType(message, messageId),
                payload,
                requiredProperty(message, METADATA_PROPERTY).getBytes(UTF_8),
                Instant.parse(requiredProperty(message, TIMESTAMP_PROPERTY))
            );
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new JmsWireFormatException("Invalid SMD event message: " + MESSAGE_ID_PROPERTY + "=" + messageId, e);
        }
    }

    private static String requiredType(Message message, String messageId) throws JMSException {
        var eventType = message.getJMSType();
        if (eventType == null || eventType.isBlank()) {
            throw new JmsWireFormatException("SMD event message has no JMSType: " + MESSAGE_ID_PROPERTY + "=" + messageId);
        }
        return eventType;
    }

    private static String requiredProperty(Message message, String property) throws JMSException {
        var value = message.getStringProperty(property);
        if (value == null) {
            throw new JmsWireFormatException("SMD event message is missing property " + property + " (JMSMessageID=" + message.getJMSMessageID() + ")");
        }
        return value;
    }

    static class JmsWireFormatException extends RuntimeException {

        JmsWireFormatException(String message) {
            super(message);
        }

        JmsWireFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
