package app.dodb.smd.api.utils;

import app.dodb.smd.api.message.Message;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.metadata.principal.Principal;

import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.List;

import static java.util.Arrays.stream;

public record MessageArgumentBinder<P, M extends Message<P, M>>(List<ArgumentExtractor<P, M>> argumentExtractors) {

    public static <P, M extends Message<P, M>> MessageArgumentBinder<P, M> fromMethodParameters(Class<P> payloadClass, Parameter[] parameters) {
        var extractors = stream(parameters)
            .map(parameter -> {
                if (MessageId.class.isAssignableFrom(parameter.getType())) {
                    return (ArgumentExtractor<P, M>) Message::messageId;
                }
                if (Metadata.class.isAssignableFrom(parameter.getType())) {
                    return (ArgumentExtractor<P, M>) Message::metadata;
                }
                if (payloadClass.isAssignableFrom(parameter.getType())) {
                    return (ArgumentExtractor<P, M>) Message::payload;
                }
                if (Principal.class.isAssignableFrom(parameter.getType())) {
                    return (ArgumentExtractor<P, M>) message -> message.metadata().principal();
                }
                if (Instant.class.isAssignableFrom(parameter.getType())) {
                    return (ArgumentExtractor<P, M>) message -> message.metadata().timestamp();
                }
                var metadataValue = parameter.getAnnotation(MetadataValue.class);
                if (metadataValue != null) {
                    String propertyName = metadataValue.value();
                    return (ArgumentExtractor<P, M>) message -> message.metadata().properties().get(propertyName);
                }
                return (ArgumentExtractor<P, M>) _ -> null;
            })
            .toList();
        return new MessageArgumentBinder<>(extractors);
    }

    public Object[] toArguments(Message<P, M> message) {
        Object[] arguments = new Object[argumentExtractors.size()];
        for (int i = 0; i < argumentExtractors.size(); i++) {
            arguments[i] = argumentExtractors.get(i).extract(message);
        }
        return arguments;
    }

    public interface ArgumentExtractor<P, M extends Message<P, M>> {

        Object extract(Message<P, M> message);
    }
}
