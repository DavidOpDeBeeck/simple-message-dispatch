package app.dodb.smd.artifactexport;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// Reads class files through the application's class loader. Debug information (line numbers, local variable names) and
// stack map frames are skipped, so a fingerprint changes when the instructions change, not when a line moves.
final class Bytecode {

    record MethodRef(String owner, String name, String descriptor) {

        MethodRef {
            requireNonNull(owner);
            requireNonNull(name);
            requireNonNull(descriptor);
        }

        static MethodRef of(Executable executable) {
            var owner = Type.getInternalName(executable.getDeclaringClass());
            if (executable instanceof Method method) {
                return new MethodRef(owner, method.getName(), Type.getMethodDescriptor(method));
            }
            if (executable instanceof Constructor<?> constructor) {
                return new MethodRef(owner, "<init>", Type.getConstructorDescriptor(constructor));
            }
            throw new IllegalArgumentException("Unsupported handler member: " + executable);
        }

        String key() {
            return name + descriptor;
        }
    }

    record Invocation(MethodRef target, boolean virtual) {
    }

    record MethodCode(MethodRef ref, List<String> instructions, List<Invocation> invocations, List<String> createdTypes) {
    }

    record ClassCode(String name, String superName, List<String> interfaces, Map<String, MethodCode> methods) {
    }

    private final ClassLoader classLoader;
    private final Map<String, Optional<ClassCode>> classes = new HashMap<>();

    Bytecode(ClassLoader classLoader) {
        this.classLoader = requireNonNull(classLoader);
    }

    Optional<ClassCode> read(String internalName) {
        return classes.computeIfAbsent(internalName, this::load);
    }

    // Finds the code a call runs when the owner doesn't declare the method itself: up the superclasses, then the
    // default methods of its interfaces. Abstract methods have no code and resolve to nothing.
    Optional<MethodCode> resolve(MethodRef ref) {
        var owner = read(ref.owner());
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        var declared = owner.get().methods().get(ref.key());
        if (declared != null) {
            return Optional.of(declared);
        }
        var candidates = new ArrayList<String>();
        if (owner.get().superName() != null) {
            candidates.add(owner.get().superName());
        }
        candidates.addAll(owner.get().interfaces());
        for (var candidate : candidates) {
            var resolved = resolve(new MethodRef(candidate, ref.name(), ref.descriptor()));
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    private Optional<ClassCode> load(String internalName) {
        if (internalName.startsWith("[")) {
            return Optional.empty();
        }
        try (InputStream stream = classLoader.getResourceAsStream(internalName + ".class")) {
            if (stream == null) {
                return Optional.empty();
            }
            var collector = new ClassCollector();
            new ClassReader(stream).accept(collector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return Optional.of(collector.result());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the class file of " + internalName.replace('/', '.'), e);
        }
    }

    private static final class ClassCollector extends ClassVisitor {

        private String name;
        private String superName;
        private List<String> interfaces;
        private final Map<String, MethodCode> methods = new LinkedHashMap<>();

        private ClassCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces == null ? List.of() : Arrays.asList(interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
            if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                return null;
            }
            var code = new MethodCode(new MethodRef(name, methodName, descriptor), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            methods.put(code.ref().key(), code);
            return new InstructionCollector(name, code);
        }

        private ClassCode result() {
            return new ClassCode(name, superName, interfaces, methods);
        }
    }

    private static final class InstructionCollector extends MethodVisitor {

        private final String className;
        private final MethodCode code;
        private final Map<Label, Integer> labels = new IdentityHashMap<>();

        private InstructionCollector(String className, MethodCode code) {
            super(Opcodes.ASM9);
            this.className = className;
            this.code = code;
        }

        private void emit(String instruction) {
            code.instructions().add(instruction);
        }

        private String label(Label label) {
            return "L" + labels.computeIfAbsent(label, unused -> labels.size());
        }

        // Lambda bodies are numbered by javac in the order of the whole class, so adding a lambda elsewhere renames
        // this one. The body is fingerprinted where it is reached, so the name itself is left out.
        private String member(String owner, String name, String descriptor) {
            var shownName = owner.equals(className) && name.startsWith("lambda$") ? "lambda$" : name;
            return owner + "." + shownName + descriptor;
        }

        private String handle(Handle handle) {
            return "handle " + handle.getTag() + " " + member(handle.getOwner(), handle.getName(), handle.getDesc());
        }

        @Override
        public void visitInsn(int opcode) {
            emit(Integer.toString(opcode));
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            emit(opcode + " " + operand);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            emit(opcode + " " + varIndex);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            emit(opcode + " " + type);
            if (opcode == Opcodes.NEW) {
                code.createdTypes().add(type);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            emit(opcode + " " + owner + "." + name + " " + descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            emit(opcode + " " + member(owner, name, descriptor));
            var virtual = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE;
            code.invocations().add(new Invocation(new MethodRef(owner, name, descriptor), virtual));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            var instruction = new StringBuilder("indy ").append(name).append(descriptor).append(' ').append(handle(bootstrapMethodHandle));
            for (var argument : bootstrapMethodArguments) {
                instruction.append(' ').append(argument instanceof Handle handle ? handle(handle) : String.valueOf(argument));
                if (argument instanceof Handle handle) {
                    var target = new MethodRef(handle.getOwner(), handle.getName(), handle.getDesc());
                    code.invocations().add(new Invocation(target, false));
                    if (handle.getTag() == Opcodes.H_NEWINVOKESPECIAL) {
                        code.createdTypes().add(handle.getOwner());
                    }
                }
            }
            emit(instruction.toString());
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            emit(opcode + " " + label(label));
        }

        @Override
        public void visitLabel(Label label) {
            emit(label(label) + ":");
        }

        @Override
        public void visitLdcInsn(Object value) {
            emit("ldc " + value.getClass().getSimpleName() + " " + (value instanceof Handle handle ? handle(handle) : value));
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            emit("iinc " + varIndex + " " + increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label defaultLabel, Label... targets) {
            var instruction = new StringBuilder("tableswitch " + min + " " + max + " " + label(defaultLabel));
            for (var target : targets) {
                instruction.append(' ').append(label(target));
            }
            emit(instruction.toString());
        }

        @Override
        public void visitLookupSwitchInsn(Label defaultLabel, int[] keys, Label[] targets) {
            var instruction = new StringBuilder("lookupswitch " + label(defaultLabel));
            for (int i = 0; i < keys.length; i++) {
                instruction.append(' ').append(keys[i]).append('=').append(label(targets[i]));
            }
            emit(instruction.toString());
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            emit("multianewarray " + descriptor + " " + numDimensions);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            emit("try " + label(start) + " " + label(end) + " " + label(handler) + " " + type);
        }
    }
}
