package app.dodb.smd.api.event.channel;

public interface EventChannelBinding<C extends EventChannel> {

    void bind(C channel, EventChannelListener listener);

    static <C extends SubscribableEventChannel> EventChannelBinding<C> direct() {
        return SubscribableEventChannel::subscribe;
    }
}
