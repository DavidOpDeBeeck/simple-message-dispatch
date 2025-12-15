package app.dodb.smd.api.message;

import app.dodb.smd.api.metadata.Metadata;

public interface Message<P, M extends Message<P, M>> {

    MessageId getMessageId();

    P getPayload();

    Metadata getMetadata();

    M withMetadata(Metadata metadata);
}
