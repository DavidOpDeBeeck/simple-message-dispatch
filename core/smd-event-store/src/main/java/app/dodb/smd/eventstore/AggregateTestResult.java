package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

public final class AggregateTestResult<A, R> {

    private final List<Event> events;
    private final R result;
    private final Optional<Exception> exception;
    private final Supplier<Optional<A>> state;

    AggregateTestResult(List<Event> events, R result, Optional<Exception> exception, Supplier<Optional<A>> state) {
        this.events = requireNonNull(events);
        this.result = result;
        this.exception = requireNonNull(exception);
        this.state = requireNonNull(state);
    }

    public AggregateTestResult<A, R> expectEvents(Event... expectedEvents) {
        var expected = List.of(expectedEvents);
        if (!events.equals(expected)) {
            throw failure("""
                Unexpected aggregate events.

                    Expected:
                    %s

                    Applied:
                    %s
                """.formatted(expected, events));
        }
        return this;
    }

    public AggregateTestResult<A, R> expectNoEvents() {
        return expectEvents();
    }

    public AggregateTestResult<A, R> expectEventsSatisfying(Consumer<? super List<Event>> assertions) {
        assertions.accept(events);
        return this;
    }

    public AggregateTestResult<A, R> expectResult(R expectedResult) {
        if (exception.isPresent()) {
            throw failure("""
                Expected a command result, but the command failed.

                    Expected:
                    %s
                """.formatted(expectedResult));
        }
        if (!Objects.equals(result, expectedResult)) {
            throw failure("""
                Unexpected command result.

                    Expected:
                    %s

                    Found:
                    %s
                """.formatted(expectedResult, result));
        }
        return this;
    }

    public AggregateTestResult<A, R> expectResultSatisfying(Consumer<? super R> assertions) {
        if (exception.isPresent()) {
            throw failure("Expected a command result, but the command failed.");
        }
        assertions.accept(result);
        return this;
    }

    public <T extends Exception> AggregateTestResult<A, R> expectException(Class<T> expectedType) {
        return expectException(expectedType, _ -> {
        });
    }

    public <T extends Exception> AggregateTestResult<A, R> expectException(Class<T> expectedType, Consumer<? super T> assertions) {
        if (exception.isEmpty()) {
            throw failure("""
                Expected the command to fail, but it succeeded.

                    Expected:
                    %s

                    Result:
                    %s
                """.formatted(expectedType.getName(), result));
        }
        if (!expectedType.isInstance(exception.get())) {
            throw failure("""
                Unexpected command failure.

                    Expected:
                    %s

                    Found:
                    %s
                """.formatted(expectedType.getName(), exception.get().getClass().getName()));
        }
        assertions.accept(expectedType.cast(exception.get()));
        return this;
    }

    public AggregateTestResult<A, R> expectState(Consumer<? super A> assertions) {
        var aggregate = state.get().orElseThrow(() -> failure("No aggregate state: neither the given events nor the command created an aggregate."));
        assertions.accept(aggregate);
        return this;
    }

    private AssertionError failure(String message) {
        var error = new AssertionError(message);
        exception.ifPresent(error::initCause);
        return error;
    }
}
