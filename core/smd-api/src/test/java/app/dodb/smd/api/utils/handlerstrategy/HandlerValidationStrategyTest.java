package app.dodb.smd.api.utils.handlerstrategy;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.metadata.MetadataValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.handlerstrategy.HandlerValidationStrategy.validateHandler;
import static java.util.Arrays.stream;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandlerValidationStrategyTest {

    @Test
    void validateHandler_atMostOneAssignableParameterWithDuplicateParameters_fails() {
        var method = method("duplicateStringParameters");

        assertThatThrownBy(() -> validateHandler(method, List.of(
            new AtMostOneAssignableParameterStrategy(String.class),
            new AtMostOneAssignableParameterStrategy(Integer.class)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must only include one parameter of type %s.".formatted(logClass(String.class)));
    }

    @Test
    void validateHandler_atLeastOneParameterWithoutParameters_fails() {
        var method = method("withoutParameters");

        assertThatThrownBy(() -> validateHandler(method, List.of(new AtLeastOneParameterStrategy())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must have at least one parameter.");
    }

    @Test
    void validateHandler_metadataValueWithBareString_fails() {
        var method = method("plainStringParameter");

        assertThatThrownBy(() -> validateHandler(method, List.of(new MetadataValueParameterStrategy())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: metadata value parameter must be annotated with @MetadataValue.");
    }

    @Test
    void validateHandler_metadataValueOnNonStringParameter_fails() {
        var method = method("nonStringMetadataValueParameter");

        assertThatThrownBy(() -> validateHandler(method, List.of(new MetadataValueParameterStrategy())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: only parameters of type String can be annotated with @MetadataValue.");
    }

    @Test
    void validateHandler_allowedParameterTypesWithUnsupportedType_fails() {
        var method = method("unsupportedParameterType");

        assertThatThrownBy(() -> validateHandler(method, List.of(new AllowedParameterTypesStrategy(Set.of(Runnable.class, String.class, Integer.class)))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: unsupported parameter types found.")
            .hasMessageContaining("Long");
    }

    @Test
    void validateHandler_publicWithPackagePrivateMethod_fails() {
        var method = method("withoutParameters");

        assertThatThrownBy(() -> validateHandler(method, List.of(new PublicStrategy())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must be public.")
            .hasMessageContaining(HandlerFixtures.class.getName() + "#withoutParameters()");
    }

    @Test
    void validateHandler_nonStaticWithStaticMethod_fails() {
        var method = method("withoutParameters");

        assertThatThrownBy(() -> validateHandler(method, List.of(new NonStaticStrategy())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must not be static.")
            .hasMessageContaining(HandlerFixtures.class.getName() + "#withoutParameters()");
    }

    @Test
    void validateHandler_returnTypeWithOtherReturnType_fails() {
        var method = method("returnsString");

        assertThatThrownBy(() -> validateHandler(method, List.of(new ReturnTypeStrategy(void.class))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: return type mismatch.")
            .hasMessageContaining(logClass(String.class));
    }

    @Test
    void validateHandler_messageReturnTypeWithOtherReturnType_fails() {
        var method = method("handleWithWrongReturnType");

        assertThatThrownBy(() -> validateHandler(method, List.of(new MessageReturnTypeStrategy(Command.class))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: return type mismatch.")
            .hasMessageContaining(logClass(Integer.class));
    }

    @Test
    void validateHandler_messageReturnTypeWithoutMessageParameter_leavesItToParameterStrategies() {
        var method = method("returnsString");

        assertThatCode(() -> validateHandler(method, List.of(new MessageReturnTypeStrategy(Command.class)))).doesNotThrowAnyException();
    }

    private static Method method(String name) {
        return stream(HandlerFixtures.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(name))
            .findFirst()
            .orElseThrow();
    }

    record StringCommand() implements Command<String> {
    }

    @SuppressWarnings("unused")
    private static class HandlerFixtures {

        static void duplicateStringParameters(String firstString, String secondString, Integer firstInteger, Integer secondInteger) {
        }

        static void plainStringParameter(String value) {
        }

        static void nonStringMetadataValueParameter(@MetadataValue("value") Integer value) {
        }

        static void unsupportedParameterType(Runnable runnable, Long value) {
        }

        static void withoutParameters() {
        }

        String returnsString() {
            return "";
        }

        Integer handleWithWrongReturnType(StringCommand command) {
            return 0;
        }
    }
}
