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
            provider.defer(() -> steps.add("outer-commit"));

            provider.doInTransaction(() -> {
                provider.defer(() -> steps.add("inner-commit"));
                steps.add("inner-body");
            });

            steps.add("outer-body");
            assertThat(steps).containsExactly("inner-body", "outer-body");
        });

        assertThat(steps).containsExactly("inner-body", "outer-body", "outer-commit", "inner-commit");
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
}
