package de.splatgames.aether.weaver.engine.merge;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.model.WeaveMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuralWeaverTest {

    private static final Path OUTPUT = compileFixtures();

    private static final ClassDesc TARGET = ClassDesc.of("mergefixture.Session");

    private static final ClassDesc WEAVE = ClassDesc.of("mergefixture.SessionTracing");

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    @Nested
    @DisplayName("the merged class loads and behaves")
    class EndToEnd {

        @Test
        @DisplayName("a merged method reads a shadowed field and gets the target's own value")
        void shadowReadsTheTargetsValue() throws Exception {
            final byte[] merged = merge(weave(shadowField(), mergedMethod(), uniqueField()));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(merged)).isEmpty();

            final Object session = instantiate(merged);
            assertThat(call(session, "describe"))
                    .as("'the-target-name' comes from the SESSION's own field, which the weave "
                            + "never initialises — a shadow that had been merged by mistake would "
                            + "read null here")
                    .isEqualTo("woven[the-target-name, calls=1]");
        }

        @Test
        @DisplayName("merged state is per-instance and survives between calls")
        void mergedStateIsPerInstance() throws Exception {
            final byte[] merged = merge(weave(shadowField(), mergedMethod(), uniqueField()));
            final Object session = instantiate(merged);

            assertThat(call(session, "describe")).isEqualTo("woven[the-target-name, calls=1]");
            assertThat(call(session, "describe"))
                    .as("a merged field is real state on the instance, not a constant folded in")
                    .isEqualTo("woven[the-target-name, calls=2]");
        }

        @Test
        @DisplayName("a merged method calling a shadowed private method reaches it")
        void shadowedPrivateMethodIsReached() throws Exception {
            final byte[] merged = merge(weave(shadowField(), shadowMethod(), callsHelper(),
                    uniqueField()));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(merged)).isEmpty();
            assertThat(call(instantiate(merged), "viaHelper"))
                    .as("the weave could never have called Session#secret from outside; after "
                            + "merging, its code IS Session")
                    .isEqualTo("secret:the-target-name");
        }

        @Test
        @DisplayName("rule R6 — the call to a shadowed private method is invokespecial")
        void shadowedPrivateMethodUsesInvokespecial() {
            final byte[] merged = merge(weave(shadowField(), shadowMethod(), callsHelper(),
                    uniqueField()));

            final List<Opcode> calls = new ArrayList<>();
            ClassFile.of().parse(merged).methods().stream()
                    .filter(method -> method.methodName().equalsString("viaHelper"))
                    .findFirst().orElseThrow()
                    .code().orElseThrow()
                    .elementList().forEach(element -> {
                        if (element instanceof InvokeInstruction invoke
                                && invoke.name().equalsString("secret")) {
                            calls.add(invoke.opcode());
                        }
                    });

            assertThat(calls)
                    .as("invokevirtual ALSO runs here — nestmate rules permit it, which is "
                            + "exactly what made spike 2 look correct. It dispatches virtually on a "
                            + "member that has no virtual dispatch, so the moment the target is "
                            + "subclassed the two disagree. The opcode comes from the resolved "
                            + "member's flags")
                    .containsExactly(Opcode.INVOKESPECIAL);
        }
    }

    @Nested
    @DisplayName("generated accessors and invokers")
    class Generated {

        @Test
        @DisplayName("an accessor reads a private field of the target")
        void accessorReadsAPrivateField() throws Exception {
            final byte[] merged = merge(weave(new WeaveMember.Accessor("getName",
                    MethodTypeDesc.of(ConstantDescs.CD_String), Set.of(AccessFlag.ABSTRACT),
                    "name")));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(merged)).isEmpty();
            assertThat(call(instantiate(merged), "getName")).isEqualTo("the-target-name");
        }

        @Test
        @DisplayName("a setter writes it")
        void accessorWritesAPrivateField() throws Exception {
            final byte[] merged = merge(weave(
                    new WeaveMember.Accessor("setLevel",
                            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int),
                            Set.of(AccessFlag.ABSTRACT), "level"),
                    new WeaveMember.Accessor("getLevel",
                            MethodTypeDesc.of(ConstantDescs.CD_int),
                            Set.of(AccessFlag.ABSTRACT), "level")));

            assertThat(reported).isEmpty();
            final Object session = instantiate(merged);
            session.getClass().getMethod("setLevel", int.class).invoke(session, 9);
            assertThat(call(session, "getLevel")).isEqualTo(9);
        }

        @Test
        @DisplayName("AW1097 — a setter for a final field is refused, because nothing else "
                + "would catch it")
        void settingAFinalFieldIsRefused() {
            merge(weave(new WeaveMember.Accessor("setName",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String),
                    Set.of(AccessFlag.ABSTRACT), "name")));

            assertThat(codes())
                    .as("found by running the generated setter: the class VERIFIES, and the JVM "
                            + "throws IllegalAccessError the first time it is called. Neither "
                            + "ClassFile.verify nor class loading says a word")
                    .contains("AW1097");
            assertThat(this_reported().getFirst().details())
                    .anySatisfy(detail -> assertThat(detail).contains("IllegalAccessError"));
        }

        @Test
        @DisplayName("an invoker calls a private method, arguments and all")
        void invokerCallsAPrivateMethod() throws Exception {
            final byte[] merged = merge(weave(new WeaveMember.Invoker("callSecret",
                    MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_long),
                    Set.of(AccessFlag.ABSTRACT), "secret")));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(merged))
                    .as("a long argument occupies two slots, so loading the parameters by index "
                            + "rather than by width produces a body that reads the high half of it")
                    .isEmpty();

            final Object session = instantiate(merged);
            assertThat(session.getClass().getMethod("callSecret", long.class)
                    .invoke(session, 7L))
                    .isEqualTo("secret:the-target-name");
        }

        @Test
        @DisplayName("a weave of only accessors needs no bytes at all")
        void accessorsNeedNoBytes() {
            final WeaveClass weave = weave(new WeaveMember.Accessor("getName",
                    MethodTypeDesc.of(ConstantDescs.CD_String), Set.of(AccessFlag.ABSTRACT),
                    "name"));
            final byte[] merged = new StructuralWeaver(WeaveBytes.NONE)
                    .apply(target(), List.of(weave), this_reporter());

            assertThat(this_reported())
                    .as("an accessor is GENERATED from its declaration's shape, so demanding a "
                            + "class file for it would make every driver carry a reader it never "
                            + "uses")
                    .isEmpty();
            assertThat(merged).isNotNull();
        }

        private Reporter this_reporter() {
            return StructuralWeaverTest.this.reporter;
        }

        private List<Diagnostic> this_reported() {
            return StructuralWeaverTest.this.reported;
        }
    }

    @Nested
    @DisplayName("through the Weaver, which is where a driver meets it")
    class ThroughTheWeaver {

        @Test
        @DisplayName("a weave that declares no injection at all is still applied")
        void aPurelyStructuralWeaveIsApplied() throws Exception {
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(weave(shadowField(), mergedMethod(), uniqueField())))
                    .weaveBytes(type -> WEAVE.equals(type)
                            ? read("mergefixture/SessionTracing.class") : null)
                    .diagnostics(StructuralWeaverTest.this.reported::add)
                    .build();

            final byte[] woven = weaver.weave("mergefixture/Session",
                    read("mergefixture/Session.class"));

            assertThat(StructuralWeaverTest.this.reported).isEmpty();
            assertThat(woven)
                    .as("such a weave produces no plan ENTRIES, so a fast path that only asked "
                            + "about entries would answer 'not woven' and be believed")
                    .isNotNull();
            assertThat(call(instantiate(woven), "describe"))
                    .isEqualTo("woven[the-target-name, calls=1]");
        }

        @Test
        @DisplayName("the fingerprint changes when the merged members change")
        void theFingerprintCoversTheStructure() {
            final String withField = fingerprintOf(weave(shadowField(), mergedMethod(),
                    uniqueField()));
            final String withoutField = fingerprintOf(weave(shadowField(), mergedMethod()));

            assertThat(withField)
                    .as("two plans whose injections are identical and whose merged members differ "
                            + "produce different classes; a fingerprint that could not tell them "
                            + "apart would make the idempotence gate skip a class that needed "
                            + "reweaving")
                    .isNotEqualTo(withoutField);
        }

        @Test
        @DisplayName("a class no weave touches still costs one lookup and returns null")
        void theFastPathStillAnswersNo() {
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(weave(shadowField(), mergedMethod(), uniqueField())))
                    .weaveBytes(WeaveBytes.NONE)
                    .build();

            assertThat(weaver.weave("java/lang/String", () -> {
                throw new AssertionError("the bytes must not be fetched for an unwoven class");
            })).isNull();
        }

        private String fingerprintOf(final WeaveClass weave) {
            return Weaver.builder().weaves(List.of(weave)).build().fingerprint();
        }
    }

    @Nested
    @DisplayName("refusals leave the class alone")
    class Refusals {

        @Test
        @DisplayName("AW1080 — a merged member colliding with the target's own")
        void collisionIsRefused() {
            merge(weave(new WeaveMember.Merged("name", ConstantDescs.CD_String,
                    Set.of(AccessFlag.PRIVATE), false, false)));

            assertThat(codes()).contains("AW1080");
        }

        @Test
        @DisplayName("AW1094 — a @Unique member colliding is renamed instead, and said so")
        void uniqueCollisionIsRenamed() {
            final byte[] merged = merge(weave(new WeaveMember.Merged("name",
                    ConstantDescs.CD_String, Set.of(AccessFlag.PRIVATE), true, false)));

            assertThat(codes()).containsExactly("AW1094");
            assertThat(merged).isNotNull();

            final List<String> fields = new ArrayList<>();
            ClassFile.of().parse(merged).fields()
                    .forEach(field -> fields.add(field.fieldName().stringValue()));
            assertThat(fields)
                    .as("the target's own field is untouched and the weave's sits beside it")
                    .contains("name")
                    .anySatisfy(name -> assertThat(name).startsWith("name$aw$"));
        }

        @Test
        @DisplayName("a name that is free is not mangled")
        void aFreeNameIsKept() {
            final byte[] merged = merge(weave(uniqueField()));

            assertThat(reported)
                    .as("@Unique is permission to rename, not a request to — a hash in every stack "
                            + "trace of the woven class is a cost with no benefit when the name "
                            + "was available")
                    .isEmpty();
            final List<String> fields = new ArrayList<>();
            ClassFile.of().parse(merged).fields()
                    .forEach(field -> fields.add(field.fieldName().stringValue()));
            assertThat(fields).contains("calls");
        }

        @Test
        @DisplayName("the mangled name is the same on every run")
        void manglingIsStable() {
            final byte[] first = merge(weave(new WeaveMember.Merged("name",
                    ConstantDescs.CD_String, Set.of(AccessFlag.PRIVATE), true, true)));
            this.reset();
            final byte[] second = merge(weave(new WeaveMember.Merged("name",
                    ConstantDescs.CD_String, Set.of(AccessFlag.PRIVATE), true, true)));

            assertThat(first)
                    .as("a counter would be shorter and would make the bytes depend on processing "
                            + "order, which is the reproducibility guarantee gone")
                    .isEqualTo(second);
        }

        @Test
        @DisplayName("AW1030 — shadowing a field the target does not have")
        void unknownShadowIsRefused() {
            final byte[] merged = merge(weave(new WeaveMember.Shadowed("ghost",
                    ConstantDescs.CD_String, Set.of(AccessFlag.PRIVATE), "ghost", false),
                    uniqueField()));

            assertThat(codes()).contains("AW1030");
            assertThat(merged)
                    .as("all or nothing: a class that gained some of a weave's members and not "
                            + "others is neither the original nor what was asked for")
                    .isNull();
        }

        @Test
        @DisplayName("AW1031 — shadowing it at the wrong type")
        void shadowTypeMismatchIsRefused() {
            merge(weave(new WeaveMember.Shadowed("name", ConstantDescs.CD_int,
                    Set.of(AccessFlag.PRIVATE), "name", false), uniqueField()));

            assertThat(codes())
                    .as("a @Shadow is a promise about the target, and it is checked where the "
                            + "promise is USED — a weave that merges nothing dissolves into "
                            + "nothing, so there is no body for the promise to be kept to")
                    .contains("AW1031");
        }

        @Test
        @DisplayName("AW1096 — merging a method without the weave's class file")
        void missingBytesAreReported() {
            final byte[] merged = new StructuralWeaver(WeaveBytes.NONE)
                    .apply(target(), List.of(weave(mergedMethod(), shadowField())), this.reporter());

            assertThat(this.codes()).contains("AW1096");
            assertThat(merged).isNull();
        }

        @Test
        @DisplayName("AW1020 — an invoker naming a method that is not there")
        void unknownInvokerIsRefused() {
            merge(weave(new WeaveMember.Invoker("callGhost",
                    MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.ABSTRACT),
                    "ghost")));

            assertThat(codes()).contains("AW1020");
        }

        private Reporter reporter() {
            return StructuralWeaverTest.this.reporter;
        }

        private List<String> codes() {
            return StructuralWeaverTest.this.codes();
        }

        private void reset() {
            StructuralWeaverTest.this.reported.clear();
        }
    }

    @Nested
    @DisplayName("@Shadow(mutable = true) removes final from the target's field")
    class MutableShadows {

        @Test
        @DisplayName("the target's field loses ACC_FINAL, and it is reported")
        void theFlagIsRemoved() {
            final byte[] merged = merge(weave(mutableShadow("name"), uniqueField()));

            assertThat(codes()).contains("AW1033");
            assertThat(isFinal(merged, "name"))
                    .as("the whole point of mutable = true is that the target's own declaration "
                            + "stops being a guarantee")
                    .isFalse();
        }

        @Test
        @DisplayName("a merged body really writes it — the class verifies AND runs")
        void theWriteSucceedsAtRuntime() throws Exception {
            final byte[] merged = merge(weave(mutableShadow("name"), renamesTheTarget()));

            assertThat(ClassFile.of().verify(merged)).isEmpty();

            final Object session = instantiate(merged);
            final Object result = session.getClass()
                    .getMethod("rename", String.class)
                    .invoke(session, "renamed");

            assertThat(result)
                    .as("verification is not the test: a putfield on a final field of the "
                            + "declaring class verifies and then throws IllegalAccessError on the "
                            + "first call, which is how AW1097 was found")
                    .isEqualTo("renamed");
        }

        @Test
        @DisplayName("the counter-probe: the same merge without mutable = true fails at runtime")
        void theSameWriteWithoutPermissionThrows() throws Exception {
            final byte[] merged = merge(weave(shadowField(), renamesTheTarget()));

            assertThat(ClassFile.of().verify(merged))
                    .as("the class file is well-formed either way — this is exactly the shape of "
                            + "failure that verification does not catch")
                    .isEmpty();

            final Object session = instantiate(merged);
            assertThatThrownBy(() -> session.getClass()
                    .getMethod("rename", String.class)
                    .invoke(session, "renamed"))
                    .as("without this, the previous test would pass whether or not the flag was "
                            + "ever removed")
                    .cause()
                    .isInstanceOf(IllegalAccessError.class)
                    .hasMessageContaining("final field");
        }

        @Test
        @DisplayName("without mutable = true the field keeps its final flag")
        void anOrdinaryShadowChangesNothing() {
            final byte[] merged = merge(weave(shadowField(), uniqueField()));

            assertThat(codes()).isEmpty();
            assertThat(isFinal(merged, "name"))
                    .as("a shadow is a promise about the target, not permission to rewrite it")
                    .isTrue();
        }

        @Test
        @DisplayName("asking for a field that was never final is a no-op, silently")
        void nothingToRemoveIsNotWorthSaying() {
            final byte[] merged = merge(weave(mutableShadow("level"), uniqueField()));

            assertThat(codes())
                    .as("mutable = true on an already-writable field costs nothing and changes "
                            + "nothing; a warning would be advice with no action behind it")
                    .isEmpty();
            assertThat(isFinal(merged, "level")).isFalse();
        }

        @Test
        @DisplayName("a weave that only asks for mutability and gains nothing rebuilds nothing")
        void aNoOpWeaveDoesNotRebuildTheClass() {
            assertThat(mergeInto(target(), weave(mutableShadow("level"))))
                    .as("a full parse-and-emit to produce the bytes it started with is work with "
                            + "no result, and a changed class where none was needed")
                    .isNull();
        }

        @Test
        @DisplayName("a weave that only asks for mutability and gets it does rebuild")
        void aMutableOnlyWeaveIsStillStructural() {
            final byte[] merged = merge(weave(mutableShadow("name")));

            assertThat(merged)
                    .as("nothing is added, but the target no longer declares what it used to")
                    .isNotNull();
            assertThat(isFinal(merged, "name")).isFalse();
        }
    }

    @Nested
    @DisplayName("targets whose shape fixes their instance state")
    class ShapedTargets {

        @Test
        @DisplayName("AW1088 — merging an instance field into a record is refused")
        void recordsAreRefused() {
            final byte[] merged = mergeInto(targetNamed("Coordinate"), weave(uniqueField()));

            assertThat(codes()).containsExactly("AW1088");
            assertThat(merged)
                    .as("a record's equals, hashCode, toString and accessors all come from its "
                            + "components, so a merged field is state every one of them ignores")
                    .isNull();
        }

        @Test
        @DisplayName("AW1089 — merging an instance field into an enum is allowed, with a warning")
        void enumsAreWarnedAbout() {
            final byte[] merged = mergeInto(targetNamed("Level"), weave(uniqueField()));

            assertThat(codes()).containsExactly("AW1089");
            assertThat(merged)
                    .as("the field works; it is merely always at its default, because the "
                            + "constants were constructed in <clinit> before anything could run")
                    .isNotNull();
            assertThat(ClassFile.of().verify(merged)).isEmpty();
        }

        @Test
        @DisplayName("a static field is neither shape's business")
        void staticFieldsAreOrdinary() {
            final byte[] merged = mergeInto(targetNamed("Coordinate"),
                    weave(new WeaveMember.Merged("registry", ConstantDescs.CD_int,
                            Set.of(AccessFlag.PRIVATE, AccessFlag.STATIC), true, false)));

            assertThat(codes())
                    .as("neither a record's component contract nor an enum's fixed set of "
                            + "instances has anything to say about a class-level member")
                    .isEmpty();
            assertThat(merged).isNotNull();
        }

        @Test
        @DisplayName("the refusal names the record and the field")
        void theDiagnosticIsSpecific() {
            mergeInto(targetNamed("Coordinate"), weave(uniqueField()));

            assertThat(this.reported()).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.message()).contains("calls", "Coordinate");
                assertThat(diagnostic.remedy().orElseThrow()).contains("static");
            });
        }

        private List<Diagnostic> reported() {
            return StructuralWeaverTest.this.reported;
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private byte[] merge(final WeaveClass weave) {
        return mergeInto(target(), weave);
    }

    private byte[] mergeInto(final ClassModel target, final WeaveClass weave) {
        final Map<ClassDesc, byte[]> known = Map.of(WEAVE, read("mergefixture/SessionTracing.class"));
        return new StructuralWeaver(known::get).apply(target, List.of(weave), this.reporter);
    }

    private static ClassModel target() {
        return ClassFile.of().parse(read("mergefixture/Session.class"));
    }

    private static ClassModel targetNamed(final String simpleName) {
        return ClassFile.of().parse(read("mergefixture/" + simpleName + ".class"));
    }

    private static WeaveMember mutableShadow(final String field) {
        return new WeaveMember.Shadowed(field,
                "name".equals(field) ? ConstantDescs.CD_String : ConstantDescs.CD_int,
                Set.of(AccessFlag.PRIVATE), field, true);
    }

    private static WeaveMember renamesTheTarget() {
        return new WeaveMember.Merged("rename",
                MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String),
                Set.of(AccessFlag.PUBLIC), false, false);
    }

    private static boolean isFinal(final byte[] merged, final String name) {
        return ClassFile.of().parse(merged).fields().stream()
                .filter(field -> field.fieldName().equalsString(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + name))
                .flags().flags().contains(AccessFlag.FINAL);
    }

    private static WeaveClass weave(final WeaveMember... members) {
        return new WeaveClass(WEAVE, List.of(new TargetRef(TARGET, true)),
                Weave.Kind.INSTANCE, 0, Require.REQUIRED, Phase.DEFAULT, Set.of(), List.of(),
                List.of(members), List.of(), Origin.of("test", null));
    }

    private static WeaveMember shadowField() {
        return new WeaveMember.Shadowed("name", ConstantDescs.CD_String,
                Set.of(AccessFlag.PRIVATE), "name", false);
    }

    private static WeaveMember shadowMethod() {
        return new WeaveMember.Shadowed("secret",
                MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_long),
                Set.of(AccessFlag.PRIVATE), "secret", false);
    }

    private static WeaveMember mergedMethod() {
        return new WeaveMember.Merged("describe", MethodTypeDesc.of(ConstantDescs.CD_String),
                Set.of(AccessFlag.PUBLIC), false, false);
    }

    private static WeaveMember callsHelper() {
        return new WeaveMember.Merged("viaHelper", MethodTypeDesc.of(ConstantDescs.CD_String),
                Set.of(AccessFlag.PUBLIC), false, false);
    }

    private static WeaveMember uniqueField() {
        return new WeaveMember.Merged("calls", ConstantDescs.CD_int,
                Set.of(AccessFlag.PRIVATE), true, false);
    }

    private static Object instantiate(final byte[] merged) throws Exception {
        final ClassLoader loader = new ClassLoader(StructuralWeaverTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                if ("mergefixture.Session".equals(name)) {
                    return defineClass(name, merged, 0, merged.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        return loader.loadClass("mergefixture.Session").getDeclaredConstructor().newInstance();
    }

    private static Object call(final Object instance, final String method) throws Exception {
        return instance.getClass().getMethod(method).invoke(instance);
    }

    private static byte[] read(final String resource) {
        try {
            return Files.readAllBytes(OUTPUT.resolve(resource));
        } catch (final Exception failed) {
            throw new AssertionError("could not read " + resource, failed);
        }
    }

    private static Path compileFixtures() {
        try {
            final Path output = Files.createTempDirectory("aether-weaver-merge");
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(null, null, null)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
                final boolean ok = compiler.getTask(null, files, null, List.of("-g"), null,
                        List.of(new TargetSource(), new WeaveSource(),
                                new Source("mergefixture/Coordinate", """
                                        package mergefixture;

                                        public record Coordinate(int x, int y) { }
                                        """),
                                new Source("mergefixture/Level", """
                                        package mergefixture;

                                        public enum Level { LOW, HIGH }
                                        """))).call();
                if (!ok) {
                    throw new AssertionError("the merge fixtures must compile");
                }
            }
            return output;
        } catch (final Exception failed) {
            throw new AssertionError("could not build the merge fixtures", failed);
        }
    }

    private static final class Source extends SimpleJavaFileObject {

        private final String code;

        Source(final String path, final String code) {
            super(URI.create("string:///" + path + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return this.code;
        }
    }

    private static final class TargetSource extends SimpleJavaFileObject {

        private static final String CODE = """
                package mergefixture;

                public class Session {

                    private final String name = "the-target-name";
                    private int level = 3;

                    private String secret(long unused) {
                        return "secret:" + this.name;
                    }
                }
                """;

        TargetSource() {
            super(URI.create("string:///mergefixture/Session.java"), Kind.SOURCE);
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return CODE;
        }
    }

    private static final class WeaveSource extends SimpleJavaFileObject {

        private static final String CODE = """
                package mergefixture;

                public final class SessionTracing {

                    // @Shadow — Session really has these.
                    private String name;
                    private String secret(long unused) { throw new AssertionError("shadow"); }

                    // @Unique — new state on Session.
                    private int calls;

                    public String describe() {
                        this.calls++;
                        return "woven[" + this.name + ", calls=" + this.calls + "]";
                    }

                    public String viaHelper() {
                        return secret(0L);
                    }

                    // Writes what Session declares final. Legal here because the local declaration
                    // is not final; whether it is legal on the target is what @Shadow(mutable) decides.
                    public String rename(String replacement) {
                        this.name = replacement;
                        return this.name;
                    }
                }
                """;

        WeaveSource() {
            super(URI.create("string:///mergefixture/SessionTracing.java"), Kind.SOURCE);
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return CODE;
        }
    }
}
