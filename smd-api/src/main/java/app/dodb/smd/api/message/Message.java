package app.dodb.smd.api.message;

import app.dodb.smd.api.metadata.Metadata;

public interface Message<P> {

    MessageId getMessageId();

    P getPayload();

    Metadata getMetadata();
}
