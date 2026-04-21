package app.dodb.smd.spring;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
    void defer_outsideScope_throwsIllegalStateException() {
        var provider = new SpringTransactionProvider();

        assertThatThrownBy(() -> provider.defer(() -> {
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("No active transaction context present");
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

    private static void handleSimulatedFailure(Runnable runnable) {
        try {
            runnable.run();
        } catch (IllegalStateException e) {
            return;
        }
        throw new AssertionError("Expected simulated failure");
    }
}
