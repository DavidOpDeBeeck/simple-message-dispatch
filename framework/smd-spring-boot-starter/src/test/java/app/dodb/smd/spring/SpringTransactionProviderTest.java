package app.dodb.smd.spring;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringTransactionProviderTest {

    @Test
    void defer_runsAtProviderRootSupplier() {
        var provider = new SpringTransactionProvider();
        var callbackExecuted = new AtomicBoolean(false);

        var result = provider.doInTransaction(() -> {
            provider.defer(() -> callbackExecuted.set(true));
            assertThat(callbackExecuted).isFalse();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(callbackExecuted).isTrue();
    }

    @Test
    void defer_runsAtProviderRootRunnable() {
        var provider = new SpringTransactionProvider();
        var steps = new ArrayList<String>();

        provider.doInTransaction(() -> {
            provider.defer(() -> steps.add("commit"));
            steps.add("body");
            assertThat(steps).containsExactly("body");
        });

        assertThat(steps).containsExactly("body", "commit");
    }

    @Test
    void nestedDoInTransaction_runsCallbacksOnlyAtOutermostBoundary() {
        var provider = new SpringTransactionProvider();
        var steps = new ArrayList<String>();

        provider.doInTransaction(() -> {
            provider.defer(() -> steps.add("root-deferred-work"));

            provider.doInTransaction(() -> {
                provider.defer(() -> steps.add("joined-deferred-work"));
                steps.add("joined-transaction-body");
            });

            steps.add("root-transaction-body");
            assertThat(steps).containsExactly("joined-transaction-body", "root-transaction-body");
        });

        assertThat(steps).containsExactly("joined-transaction-body", "root-transaction-body", "root-deferred-work", "joined-deferred-work");
    }

    @Test
    void nestedDoInTransactionRunnable_whenNestedFailureIsHandled_keepsDeferredWorkInRootContext() {
        var provider = new SpringTransactionProvider();
        var deferredCallbacks = new ArrayList<String>();

        provider.doInTransaction(() -> {
            provider.defer(() -> deferredCallbacks.add("root deferred callback"));

            handleSimulatedFailure(() -> provider.doInTransaction(() -> {
                provider.defer(() -> deferredCallbacks.add("joined deferred callback"));
                throw new IllegalStateException();
            }));

            assertThat(deferredCallbacks).isEmpty();
        });

        assertThat(deferredCallbacks).containsExactly("root deferred callback", "joined deferred callback");
    }

    @Test
    void nestedDoInNewTransactionSupplier_runsCallbacksAtInnerBoundary() {
        var provider = new SpringTransactionProvider();
        var steps = new ArrayList<String>();

        provider.doInTransaction(() -> {
            provider.defer(() -> steps.add("root-deferred-work"));

            var result = provider.doInNewTransaction(() -> {
                provider.defer(() -> steps.add("requires-new-deferred-work"));
                steps.add("requires-new-body");
                assertThat(steps).containsExactly("requires-new-body");
                return "requires-new-result";
            });

            assertThat(result).isEqualTo("requires-new-result");
            assertThat(steps).containsExactly("requires-new-body", "requires-new-deferred-work");

            steps.add("root-transaction-body");
            assertThat(steps).containsExactly("requires-new-body", "requires-new-deferred-work", "root-transaction-body");
        });

        assertThat(steps).containsExactly("requires-new-body", "requires-new-deferred-work", "root-transaction-body", "root-deferred-work");
    }

    @Test
    void nestedDoInNewTransactionRunnable_whenNestedFailureIsHandled_discardsDeferredWorkFromFailedContext() {
        var provider = new SpringTransactionProvider();
        var deferredCallbacks = new ArrayList<String>();

        provider.doInTransaction(() -> {
            provider.defer(() -> deferredCallbacks.add("root deferred callback"));

            handleSimulatedFailure(() -> provider.doInNewTransaction(() -> {
                provider.defer(() -> deferredCallbacks.add("requires-new deferred callback"));
                throw new IllegalStateException();
            }));

            assertThat(deferredCallbacks).isEmpty();
        });

        assertThat(deferredCallbacks).containsExactly("root deferred callback");
    }

    @Test
    void defer_outsideScope_runsCallback() {
        var provider = new SpringTransactionProvider();
        var deferredCallbacks = new ArrayList<String>();

        provider.defer(() -> deferredCallbacks.add("deferred callback"));

        assertThat(deferredCallbacks).containsExactly("deferred callback");
    }

    @Test
    void defer_onOtherThreadWithinScope_runsCallbackImmediately() {
        var provider = new SpringTransactionProvider();
        var steps = new CopyOnWriteArrayList<String>();

        provider.doInTransaction(() -> {
            var otherThread = Thread.ofVirtual().start(() -> provider.defer(() -> steps.add("other-thread-callback")));
            join(otherThread);
            steps.add("root-transaction-body");
        });

        assertThat(steps).containsExactly("other-thread-callback", "root-transaction-body");
    }

    @Test
    void defer_callbacksRunInOrder() {
        var provider = new SpringTransactionProvider();
        var callbackOrder = new ArrayList<Integer>();

        provider.doInTransaction(() -> {
            provider.defer(() -> callbackOrder.add(1));
            provider.defer(() -> callbackOrder.add(2));
        });

        assertThat(callbackOrder).containsExactly(1, 2);
    }

    @Test
    void defer_callbackException_propagates() {
        var provider = new SpringTransactionProvider();
        var secondCallbackExecuted = new AtomicBoolean(false);

        assertThatThrownBy(() -> provider.doInTransaction(() -> {
            provider.defer(() -> {
                throw new IllegalStateException("boom");
            });
            provider.defer(() -> secondCallbackExecuted.set(true));
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom");

        assertThat(secondCallbackExecuted).isFalse();
    }

    @Test
    void resource_withinScopeAndJoinedTransaction_sharesValue() {
        var provider = new SpringTransactionProvider();
        var key = new Object();

        provider.doInTransaction(() -> {
            List<String> resource = provider.resource(key, ArrayList::new);
            resource.add("root");
            provider.doInTransaction(() -> provider.<List<String>>resource(key, ArrayList::new).add("joined"));

            assertThat(provider.<List<String>>resource(key, ArrayList::new)).isSameAs(resource).containsExactly("root", "joined");
        });
    }

    @Test
    void resource_acrossScopes_returnsSeparateValues() {
        var provider = new SpringTransactionProvider();
        var key = new Object();

        var first = provider.doInTransaction(() -> provider.resource(key, Object::new));
        var second = provider.doInTransaction(() -> provider.resource(key, Object::new));

        assertThat(first).isNotSameAs(second);
    }

    @Test
    void resource_inNewTransaction_returnsSeparateValue() {
        var provider = new SpringTransactionProvider();
        var key = new Object();

        provider.doInTransaction(() -> {
            var outer = provider.resource(key, Object::new);
            var inner = provider.doInNewTransaction(() -> provider.resource(key, Object::new));

            assertThat(inner).isNotSameAs(outer);
            assertThat(provider.resource(key, Object::new)).isSameAs(outer);
        });
    }

    @Test
    void resource_afterFailedScope_returnsFreshValue() {
        var provider = new SpringTransactionProvider();
        var key = new Object();
        var failedScopeResource = new ArrayList<Object>();

        handleSimulatedFailure(() -> provider.doInTransaction(() -> {
            failedScopeResource.add(provider.resource(key, Object::new));
            throw new IllegalStateException();
        }));
        var resource = provider.doInTransaction(() -> provider.resource(key, Object::new));

        assertThat(resource).isNotSameAs(failedScopeResource.getFirst());
    }

    @Test
    void resource_outsideScope_returnsFreshValue() {
        var provider = new SpringTransactionProvider();
        var key = new Object();

        var first = provider.resource(key, Object::new);
        var second = provider.resource(key, Object::new);

        assertThat(first).isNotSameAs(second);
    }

    private static void join(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void handleSimulatedFailure(Runnable runnable) {
        try {
            runnable.run();
        } catch (IllegalStateException e) {
            return;
        }
        throw new AssertionError("Expected simulated failure");
    }
}
