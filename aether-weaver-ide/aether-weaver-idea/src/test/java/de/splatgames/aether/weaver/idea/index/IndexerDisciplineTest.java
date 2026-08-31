package de.splatgames.aether.weaver.idea.index;

import junit.framework.TestCase;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IndexerDisciplineTest extends TestCase {

    private static final List<Class<?>> INDEXES =
            List.of(ExtensionReceiverIndex.class, WeaveTargetIndex.class);

    private static final ClassDesc PSI_CLASS = ClassDesc.of("com.intellij.psi.PsiClass");

    private static final ClassDesc PSI_METHOD = ClassDesc.of("com.intellij.psi.PsiMethod");

    private static final Set<String> FORBIDDEN = Set.of(
            "getMethods", "getFields", "getInnerClasses", "findMethodsByName", "findFieldByName",
            "getAllMethods", "getAllFields",
            "resolve", "advancedResolve", "multiResolve",
            "findClass", "findClasses", "findAnnotation", "getQualifiedName",
            "findAttributeValue");

    public void testNoIndexerAugmentsOrResolves() throws IOException {
        final List<String> violations = new ArrayList<>();
        for (final Class<?> index : INDEXES) {
            violations.addAll(violationsIn(index));
        }

        assertEquals("an indexer reached a PSI call that augments or resolves. Both are forbidden "
                        + "while indexing, and the failure they produce is a log line on every "
                        + "keystroke in a real IDE that no fixture in this suite reproduces:\n"
                        + String.join("\n", violations),
                List.of(), violations);
    }

    public void testTheScanCanSeeAViolation() {
        final byte[] offender = ClassFile.of().build(ClassDesc.of("probe.FakeIndex"),
                builder -> builder.withMethodBody("lambda$getIndexer$0",
                        MethodTypeDesc.of(ConstantDescs.CD_void, PSI_CLASS),
                        ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC,
                        code -> code
                                .aload(0)
                                .invokeinterface(PSI_CLASS, "getMethods",
                                        MethodTypeDesc.of(PSI_METHOD.arrayType()))
                                .pop()
                                .return_()));

        final List<String> found =
                violationsIn(ClassFile.of().parse(offender), "FakeIndex");
        assertEquals("a scan that cannot see this has nothing to say about a real indexer either: "
                        + found, 1, found.size());
        assertTrue(found.getFirst(), found.getFirst().contains("PsiClass.getMethods"));
    }

    // --- the scan --------------------------------------------------------------------------------

    private static List<String> violationsIn(final Class<?> index) throws IOException {
        return violationsIn(parse(index), index.getSimpleName());
    }

    private static List<String> violationsIn(final ClassModel model, final String label) {
        final Map<String, List<InvokeInstruction>> calls = callsOf(model);

        final Set<String> reached = new HashSet<>();
        for (final String name : calls.keySet()) {
            // The indexer is a lambda of getIndexer(); javac names it lambda$getIndexer$<n>.
            if (name.startsWith("lambda$getIndexer$")) {
                reached.addAll(reachableFrom(calls, name));
            }
        }
        assertFalse("no indexer lambda was found in " + label
                + "; the scan is looking for the wrong thing", reached.isEmpty());

        final List<String> violations = new ArrayList<>();
        for (final String call : forbiddenIn(calls, reached)) {
            violations.add(label + ": " + call);
        }
        return violations;
    }

    private static List<String> forbiddenIn(final Map<String, List<InvokeInstruction>> calls,
                                            final Set<String> methods) {
        final List<String> found = new ArrayList<>();
        for (final String method : methods) {
            for (final InvokeInstruction invoke : calls.getOrDefault(method, List.of())) {
                final String owner = invoke.owner().asInternalName();
                final String name = invoke.name().stringValue();
                if (owner.startsWith("com/intellij/psi/") && FORBIDDEN.contains(name)) {
                    found.add(method + " calls " + owner + '.' + name);
                }
            }
        }
        return found;
    }

    private static Set<String> reachableFrom(final Map<String, List<InvokeInstruction>> calls,
                                             final String from) {
        final Set<String> reached = new HashSet<>();
        final Deque<String> pending = new ArrayDeque<>();
        pending.add(from);
        while (!pending.isEmpty()) {
            final String method = pending.removeFirst();
            if (!reached.add(method)) {
                continue;
            }
            for (final InvokeInstruction invoke : calls.getOrDefault(method, List.of())) {
                final String name = invoke.name().stringValue();
                if (calls.containsKey(name) && !reached.contains(name)) {
                    pending.add(name);
                }
            }
        }
        return reached;
    }

    private static Map<String, List<InvokeInstruction>> callsOf(final ClassModel model) {
        final Map<String, List<InvokeInstruction>> calls = new HashMap<>();
        for (final MethodModel method : model.methods()) {
            final List<InvokeInstruction> invocations =
                    calls.computeIfAbsent(method.methodName().stringValue(),
                            key -> new ArrayList<>());
            method.code().ifPresent(code -> code.elementStream()
                    .filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast)
                    .forEach(invocations::add));
        }
        return calls;
    }

    private static ClassModel parse(final Class<?> type) throws IOException {
        final String resource = '/' + type.getName().replace('.', '/') + ".class";
        try (InputStream in = IndexerDisciplineTest.class.getResourceAsStream(resource)) {
            assertNotNull("no class file for " + type.getName(), in);
            return ClassFile.of().parse(in.readAllBytes());
        }
    }
}
