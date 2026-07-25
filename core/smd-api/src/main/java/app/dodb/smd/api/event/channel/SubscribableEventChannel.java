package app.dodb.smd.api.event.channel;

public interface SubscribableEventChannel extends EventChannel {

    void subscribe(EventChannelListener listener);
}
