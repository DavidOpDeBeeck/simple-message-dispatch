package app.dodb.smd.api.event;

public interface ProcessingGroupInterceptor {

    <E extends Event> void intercept(ProcessingGroupContext<E> context, ProcessingGroupInterceptorChain<E> chain);
}
