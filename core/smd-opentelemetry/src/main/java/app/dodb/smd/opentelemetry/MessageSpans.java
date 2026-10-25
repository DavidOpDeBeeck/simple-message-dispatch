package app.dodb.smd.opentelemetry;

import io.opentelemetry.api.common.AttributeKey;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

public final class MessageSpans {

    public static final String INSTRUMENTATION_SCOPE = "app.dodb.smd";

    public static final AttributeKey<String> MESSAGING_SYSTEM = stringKey("messaging.system");
    public static final AttributeKey<String> OPERATION_TYPE = stringKey("messaging.operation.type");
    public static final AttributeKey<String> OPERATION_NAME = stringKey("messaging.operation.name");
    public static final AttributeKey<String> DESTINATION_NAME = stringKey("messaging.destination.name");
    public static final AttributeKey<String> MESSAGE_ID = stringKey("messaging.message.id");
    public static final AttributeKey<String> PROCESSING_GROUP = stringKey("messaging.consumer.group.name");
    public static final AttributeKey<String> MESSAGE_KIND = stringKey("messaging.smd.message.kind");
    public static final AttributeKey<String> ROUTE = stringKey("messaging.smd.route");
    public static final AttributeKey<String> DELIVERY = stringKey("messaging.smd.delivery");
    public static final AttributeKey<String> THREAD_NAME = stringKey("thread.name");
    public static final AttributeKey<String> ERROR_TYPE = stringKey("error.type");

    public static final String SYSTEM = "smd";
    public static final String SEND = "send";
    public static final String PUBLISH = "publish";
    public static final String PROCESS = "process";
    public static final String COMMAND = "command";
    public static final String QUERY = "query";
    public static final String EVENT = "event";

    private MessageSpans() {
    }
}
