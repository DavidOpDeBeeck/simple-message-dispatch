package app.dodb.smd.api.command.bus;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public record CommandBusInterceptorChain<R, C extends Command<R>>(Function<CommandMessage<R, C>, R> delegate, Deque<CommandBusInterceptor> interceptors) {

    public static <R, C extends Command<R>> CommandBusInterceptorChain<R, C> create(Function<CommandMessage<R, C>, R> delegate, List<CommandBusInterceptor> interceptors) {
        return new CommandBusInterceptorChain<>(delegate, new ArrayDeque<>(interceptors));
    }

    public CommandBusInterceptorChain {
        requireNonNull(delegate);
        requireNonNull(interceptors);
    }

    public R proceed(CommandMessage<R, C> commandMessage) {
        if (interceptors.isEmpty()) {
            return delegate.apply(commandMessage);
        }
        return interceptors.pop().intercept(commandMessage, this);
    }
}
