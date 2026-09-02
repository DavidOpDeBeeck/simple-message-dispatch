package app.dodb.smd.api.event.channel;

public interface EventSource {

    void subscribe(EventChannelListener listener);
}
