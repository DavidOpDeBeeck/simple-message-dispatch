package app.dodb.smd.artifactexport;

import app.dodb.smd.artifactexport.Bytecode.MethodCode;
import app.dodb.smd.artifactexport.Bytecode.MethodRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

// The code a handler method can run: the method itself and every method it calls that the application declares,
// followed transitively. Calls into libraries (the buses, JDBC, Spring) are not followed, and neither are calls to
// abstract or interface methods, whose implementation the bytecode doesn't name.
final class CodeReach {

    private static final List<String> WRITE_PREFIXES = List.of(
        "save", "insert", "update", "upsert", "delete", "remove", "persist", "store", "put", "add", "create",
        "increment", "decrement", "mark", "append", "record", "clear", "merge", "write"
    );

    private final Map<MethodRef, MethodCode> reached = new LinkedHashMap<>();
    private final Set<String> createdTypes = new LinkedHashSet<>();
    private final Set<String> invokedOwners = new LinkedHashSet<>();
    private final Set<String> writtenOwners = new LinkedHashSet<>();

    static CodeReach from(List<MethodRef> roots, Bytecode bytecode, Predicate<String> isApplicationType) {
        var reach = new CodeReach();
        for (var root : roots) {
            reach.follow(root, bytecode, isApplicationType);
        }
        return reach;
    }

    private void follow(MethodRef ref, Bytecode bytecode, Predicate<String> isApplicationType) {
        if (reached.containsKey(ref)) {
            return;
        }
        var resolved = bytecode.resolve(ref);
        if (resolved.isEmpty()) {
            return;
        }
        var code = resolved.get();
        reached.put(ref, code);
        createdTypes.addAll(code.createdTypes());
        for (var invocation : code.invocations()) {
            var target = invocation.target();
            if (!isApplicationType.test(target.owner())) {
                continue;
            }
            invokedOwners.add(target.owner());
            if (invocation.virtual() && WRITE_PREFIXES.stream().anyMatch(target.name()::startsWith)) {
                writtenOwners.add(target.owner());
            }
            follow(target, bytecode, isApplicationType);
        }
    }

    Set<String> createdTypes() {
        return createdTypes;
    }

    Set<String> invokedOwners() {
        return invokedOwners;
    }

    Set<String> writtenOwners() {
        return writtenOwners;
    }

    // The instructions of the reached methods that belong to the handler's own class or the classes nested in it,
    // in the order they are reached.
    String fingerprint(String ownerClass) {
        var text = new StringBuilder();
        reached.values().forEach(code -> {
            var owner = code.ref().owner();
            if (owner.equals(ownerClass) || owner.startsWith(ownerClass + "$")) {
                text.append(code.ref().owner()).append('.').append(code.ref().name().startsWith("lambda$") ? "lambda$" : code.ref().name())
                    .append(code.ref().descriptor()).append('\n');
                code.instructions().forEach(instruction -> text.append("  ").append(instruction).append('\n'));
            }
        });
        return hash(text.toString());
    }

    static String hash(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static String combinedHash(List<String> fingerprints) {
        return hash(String.join("\n", requireNonNull(fingerprints)));
    }
}
