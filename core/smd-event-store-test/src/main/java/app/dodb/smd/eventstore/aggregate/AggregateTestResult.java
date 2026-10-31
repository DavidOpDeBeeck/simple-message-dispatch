package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.eventstore.aggregate.AggregateTestResult.Outcome.Failed;
import app.dodb.smd.eventstore.aggregate.AggregateTestResult.Outcome.Succeeded;
import org.assertj.core.api.ListAssert;
import org.assertj.core.api.ObjectAssert;
import org.assertj.core.api.ThrowableAssert;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;

public class AggregateTestResult<A, R> {

    private final List<Event> events;
    private final Outcome<R> outcome;
    private final Supplier<Optional<A>> state;

    AggregateTestResult(List<Event> events, Outcome<R> outcome, Supplier<Optional<A>> state) {
        this.events = requireNonNull(events);
        this.outcome = requireNonNull(outcome);
        this.state = requireNonNull(state);
    }

    public AggregateTestResult<A, R> expectEvents(Event... expectedEvents) {
        return expectEventsSatisfying(applied -> applied.containsExactly(expectedEvents));
    }

    public AggregateTestResult<A, R> expectNoEvents() {
        return expectEventsSatisfying(ListAssert::isEmpty);
    }

    public AggregateTestResult<A, R> expectEventsSatisfying(Consumer<ListAssert<Event>> assertions) {
        requireSuccess("events");
        assertions.accept(assertThat(events).as("Applied aggregate events"));
        return this;
    }

    public AggregateTestResult<A, R> expectResult(R expectedResult) {
        return expectResultSatisfying(found -> found.isEqualTo(expectedResult));
    }

    public AggregateTestResult<A, R> expectResultSatisfying(Consumer<ObjectAssert<R>> assertions) {
        var result = requireSuccess("a result");
        assertions.accept(assertThatObject(result).as("Command result"));
        return this;
    }

    public <T extends Exception> AggregateTestResult<A, R> expectException(Class<T> expectedType) {
        return expectException(expectedType, _ -> {
        });
    }

    public <T extends Exception> AggregateTestResult<A, R> expectException(Class<T> expectedType, Consumer<ThrowableAssert<T>> assertions) {
        var exception = switch (outcome) {
            case Failed<R> failed -> failed.exception();
            case Succeeded<R> succeeded -> throw new AssertionError("""
                Expected the command to fail, but it succeeded.

                    Expected:
                    %s

                    Result:
                    %s
                """.formatted(expectedType.getName(), succeeded.result()));
        };
        assertThat(exception).as("Command exception").isInstanceOf(expectedType);
        assertions.accept(new ThrowableAssert<>(expectedType.cast(exception)));
        return this;
    }

    public AggregateTestResult<A, R> expectState(Consumer<ObjectAssert<A>> assertions) {
        requireSuccess("aggregate state");
        var aggregate = state.get().orElseThrow(() -> new AssertionError("No aggregate state: neither the given events nor the command created an aggregate."));
        assertions.accept(assertThatObject(aggregate).as("Aggregate state"));
        return this;
    }

    private R requireSuccess(String expectation) {
        return switch (outcome) {
            case Succeeded<R> succeeded -> succeeded.result();
            case Failed<R> failed -> throw new AssertionError("""
                Expected %s, but the command failed.

                    Exception:
                    %s
                """.formatted(expectation, failed.exception()), failed.exception());
        };
    }

    sealed interface Outcome<R> {

        record Succeeded<R>(R result) implements Outcome<R> {
        }

        record Failed<R>(Exception exception) implements Outcome<R> {

            public Failed {
                requireNonNull(exception);
            }
        }
    }
}
