package de.splatgames.aether.weaver.api;

import de.splatgames.aether.weaver.api.callback.Callback;
import de.splatgames.aether.weaver.api.callback.ReturnableCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the reflective shape of the annotation surface the api module publishes.
 *
 * <p>Nothing here weaves anything. Every case reads a compiled annotation type through
 * {@link java.lang.reflect} and asserts a property of the declaration itself: its retention, the program
 * elements it may be written on, the shape of its repeatable container, and the default value of each
 * element. These are the parts of an annotation that a caller depends on and that no compiler error
 * protects, because changing them keeps every weave in the repository compiling and changes what the
 * engine sees.
 *
 * <h2>What the scan covers, and what it does not</h2>
 *
 * <p>Two cases -- {@link #everyAnnotationHasRuntimeRetention()} and
 * {@link #repeatableContainersAreWellFormed()} -- iterate {@link #annotationTypes()}, which walks the
 * module's own {@code target/classes} directory. They therefore cover whatever the module compiled,
 * nested annotation types included, and an annotation added later is held to them without an edit here.
 * Every other case names its annotations one by one, so it says nothing about an annotation it does not
 * mention.
 *
 * <p>The scan reads only the api module's output. A weave annotation declared anywhere else is outside
 * it.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
class AnnotationSurfaceTest {

    /**
     * Asserts that every annotation type in the module declares {@code @Retention(RUNTIME)}.
     *
     * <p>The retention decides which class file attribute javac writes the annotation into, and the
     * engine's weave parser reads {@code RuntimeVisibleAnnotations} alone. An annotation dropped to
     * {@link RetentionPolicy#CLASS} would land in {@code RuntimeInvisibleAnnotations} instead, where the
     * parser never looks: the weave would compile, carry its declaration in the class file, and be
     * skipped in silence.
     *
     * <p>Each offender is collected rather than asserted on the spot, so a failure names all of them and
     * says for each whether the annotation carries the wrong policy or no {@link Retention} at all.
     */
    @Test
    @DisplayName("every annotation is retained at runtime")
    void everyAnnotationHasRuntimeRetention() {
        final List<String> wrong = new ArrayList<>();

        for (final Class<?> type : annotationTypes()) {
            final Retention retention = type.getAnnotation(Retention.class);
            if (retention == null || retention.value() != RetentionPolicy.RUNTIME) {
                wrong.add(type.getName() + " -> "
                        + (retention == null ? "no @Retention" : retention.value().toString()));
            }
        }

        assertThat(wrong)
                .as("the engine reads these annotations structurally out of the class file, so "
                        + "anything less than RUNTIME retention makes a weave invisible to it "
                        + "without producing a single compiler warning")
                .isEmpty();
    }

    /**
     * Asserts that {@link At} and {@link Slice} declare {@code @Target({})}.
     *
     * <p>An empty target list is the only way to say that an annotation type exists to be nested inside
     * another annotation and may not be written on a program element. Widening either one to accept, say,
     * a method would make {@code @At(Point.HEAD)} compile as a standalone annotation on a member, where it
     * describes nothing and nothing reads it.
     *
     * <p>Only these two are checked. {@code Woven.Entry} declares an empty target as well and is not part
     * of this case.
     */
    @Test
    @DisplayName("@At and @Slice are usable only nested inside another annotation")
    void nestedOnlyAnnotationsDeclareAnEmptyTarget() {
        for (final Class<? extends Annotation> type : List.of(At.class, Slice.class)) {
            final Target target = type.getAnnotation(Target.class);
            assertThat(target).as("%s must declare @Target", type.getSimpleName()).isNotNull();
            assertThat(target.value())
                    .as("%s describes a position inside a handler declaration and means nothing "
                            + "on its own", type.getSimpleName())
                    .isEmpty();
        }
    }

    /**
     * Asserts the exact {@link ElementType} set of the nine annotations named in it.
     *
     * <p>Each assertion is exact rather than a containment check, so a widened target fails here as well
     * as a narrowed one. Widening is the direction that matters: an annotation that becomes writable on a
     * new kind of element gains a position the processor and the engine have no reader for, and the
     * declaration is then accepted by javac and ignored downstream.
     *
     * <p>{@link Shadow} and {@link Unique} are checked in any order because they name two element types;
     * the rest name one.
     *
     * <p>The list is written out, so it covers those nine and no others. {@link Wrap}, {@link Result},
     * {@link Woven} and the annotations of the {@code experimental} package, such as
     * {@link de.splatgames.aether.weaver.api.experimental.Extension}, declare targets that no case here
     * reads.
     */
    @Test
    @DisplayName("annotations that are written by hand target the element they describe")
    void applicableAnnotationsTargetTheRightElement() {
        assertThat(targetsOf(Weave.class)).containsExactly(ElementType.TYPE);
        assertThat(targetsOf(Group.class)).containsExactly(ElementType.TYPE);
        assertThat(targetsOf(Inject.class)).containsExactly(ElementType.METHOD);
        assertThat(targetsOf(Redirect.class)).containsExactly(ElementType.METHOD);
        assertThat(targetsOf(Accessor.class)).containsExactly(ElementType.METHOD);
        assertThat(targetsOf(Invoker.class)).containsExactly(ElementType.METHOD);
        assertThat(targetsOf(Local.class)).containsExactly(ElementType.PARAMETER);
        assertThat(targetsOf(Shadow.class))
                .containsExactlyInAnyOrder(ElementType.FIELD, ElementType.METHOD);
        assertThat(targetsOf(Unique.class))
                .containsExactlyInAnyOrder(ElementType.FIELD, ElementType.METHOD);
    }

    /**
     * Asserts that each {@link Repeatable} annotation found by the scan has a usable container.
     *
     * <p>Three properties, all of which javac would otherwise let through: the container is nested inside
     * the annotation it holds, so it never appears as a type of the package in its own right; the
     * container is retained at runtime like its contents, since a container written at a weaker retention
     * would swallow every repeated declaration; and the container's {@code value()} returns an array of
     * the repeated type.
     *
     * <p>{@link Group} and {@link Inject} are the annotations that carry {@link Repeatable} today. Any
     * other type in the scan is skipped rather than failed, so the case grows with the module.
     *
     * <p>A container with no {@link Retention} at all fails as a {@link NullPointerException} out of the
     * second assertion rather than as an assertion failure, because the annotation is dereferenced before
     * it is asserted on.
     */
    @Test
    @DisplayName("every repeatable annotation has a well-formed container")
    void repeatableContainersAreWellFormed() {
        for (final Class<?> type : annotationTypes()) {
            final Repeatable repeatable = type.getAnnotation(Repeatable.class);
            if (repeatable == null) {
                continue;
            }
            final Class<? extends Annotation> container = repeatable.value();
            assertThat(container.getEnclosingClass())
                    .as("%s's container should be nested inside it, so that it never appears in "
                            + "the package's own type list", type.getSimpleName())
                    .isEqualTo(type);
            assertThat(container.getAnnotation(Retention.class).value())
                    .as("%s must be retained like the annotation it holds", container.getName())
                    .isEqualTo(RetentionPolicy.RUNTIME);
            assertThat(valueElementOf(container).getReturnType())
                    .as("%s.value() must be an array of %s", container.getName(),
                            type.getSimpleName())
                    .isEqualTo(type.arrayType());
        }
    }

    /**
     * Asserts the declared default of thirteen annotation elements.
     *
     * <p>An element's default is part of the published contract and is invisible at every call site that
     * omits it, so changing one silently changes the meaning of declarations already written. Two of the
     * thirteen carry their reason with them: {@code At.ordinal()} defaults to {@code -1} rather than to
     * {@code 0}, which would bind every unqualified point to the first match instead of keeping them all,
     * and {@code Shadow.mutable()} defaults to {@code false}, so stripping {@code final} from a target
     * field is never something a declaration gets without asking.
     *
     * <p>{@link #defaultOf(Class, String)} fails the case when an element has no default at all, which is
     * the other half of the same guarantee: an element that loses its default turns every existing
     * declaration into a compile error.
     */
    @Test
    @DisplayName("the documented defaults are the actual defaults")
    void documentedDefaultsHold() {
        assertThat(defaultOf(Weave.class, "kind")).isEqualTo(Weave.Kind.INSTANCE);
        assertThat(defaultOf(Weave.class, "require")).isEqualTo(Require.REQUIRED);
        assertThat(defaultOf(Weave.class, "phase")).isEqualTo(Phase.DEFAULT);
        assertThat(defaultOf(Weave.class, "priority")).isEqualTo(0);

        assertThat(defaultOf(At.class, "value")).isEqualTo(Point.HEAD);
        assertThat(defaultOf(At.class, "shift")).isEqualTo(At.Shift.NONE);
        assertThat(defaultOf(At.class, "access")).isEqualTo(At.Access.ANY);
        assertThat(defaultOf(At.class, "ordinal"))
                .as("-1 keeps every match; defaulting to 0 would silently bind to the first")
                .isEqualTo(-1);

        assertThat(defaultOf(Local.class, "index")).isEqualTo(-1);
        assertThat(defaultOf(Local.class, "ordinal")).isEqualTo(-1);

        assertThat(defaultOf(Group.class, "min")).isEqualTo(1);
        assertThat(defaultOf(Group.class, "max")).isEqualTo(0);

        assertThat(defaultOf(Shadow.class, "mutable"))
                .as("removing final from a target field is never the default")
                .isEqualTo(false);
    }

    /**
     * Asserts that {@code require()} and {@code allow()} on {@link Inject} and {@link Redirect} both
     * default to {@code 0}.
     *
     * <p>The two zeroes do not mean the same thing. {@code allow() == 0} is the value: no upper bound on
     * the number of matches. {@code require() == 0} is a sentinel that leaves the real default to the
     * injector, which is what lets an explicitly written {@code 0} mean that no match is required while an
     * omitted element still requires one. Giving {@code require()} a non-zero default would erase that
     * distinction, because a class file records only the elements that were written.
     *
     * <p>{@link Wrap} declares the same two elements and is not in the list this case iterates.
     */
    @Test
    @DisplayName("require and allow default to zero so the injector supplies the real default")
    void handlerAccountingDefaultsAreDelegated() {
        for (final Class<? extends Annotation> type : List.of(Inject.class, Redirect.class)) {
            assertThat(defaultOf(type, "require"))
                    .as("%s.require() defaults to 0, which the injector reads as 'one' — the "
                            + "sentinel exists so that an explicit 0 can mean 'genuinely optional'",
                            type.getSimpleName())
                    .isEqualTo(0);
            assertThat(defaultOf(type, "allow"))
                    .as("%s.allow() defaults to unbounded", type.getSimpleName())
                    .isEqualTo(0);
        }
    }

    /**
     * Asserts that {@link At} names a slice by identifier rather than holding one.
     *
     * <p>An annotation type may not contain an element of its own type, directly or through another
     * annotation, so an {@link At} holding a {@link Slice} that holds an {@link At} does not compile. The
     * way out is the one asserted here: {@link At} declares no element whose type is {@link Slice} and
     * carries a {@code slice()} identifier defaulting to the empty string, while the annotations that own
     * the slices declare them as a {@code Slice[]}.
     *
     * <p>{@link Inject} and {@link Redirect} are the two checked for that array element. {@link Wrap}
     * declares one too and is not asserted on.
     */
    @Test
    @DisplayName("no annotation element type is cyclic, so @Slice stays reachable by name")
    void sliceIsReferencedByIdRatherThanNested() {
        assertThat(At.class.getDeclaredMethods())
                .as("an annotation type may not contain an element of its own type, directly or "
                        + "indirectly; @At holding a @Slice that holds an @At does not compile, so "
                        + "@At names a slice instead")
                .noneMatch(m -> m.getReturnType() == Slice.class);
        assertThat(defaultOf(At.class, "slice")).isEqualTo("");
        assertThat(elementOf(Inject.class, "slice").getReturnType()).isEqualTo(Slice[].class);
        assertThat(elementOf(Redirect.class, "slice").getReturnType()).isEqualTo(Slice[].class);
    }

    /**
     * Asserts that {@link Phase#EARLY} precedes {@link Phase#DEFAULT} in declaration order.
     *
     * <p>The assertion compares this one pair by {@link Enum#ordinal()}. A phase added between them, or
     * after them, is ordered by where it is written and is not checked here.
     */
    @Test
    @DisplayName("Phase constants are ordered earliest first")
    void phaseIsOrderedEarliestFirst() {
        assertThat(Phase.EARLY.ordinal())
                .as("a weave applies when the weaver's phase is at or after its own, so the "
                        + "declaration order is the gate")
                .isLessThan(Phase.DEFAULT.ordinal());
    }

    /**
     * Asserts that {@link Callback} is sealed to {@link ReturnableCallback} and that
     * {@link ReturnableCallback} is not sealed in turn.
     *
     * <p>The pair has to hold in both directions. {@link Callback} is sealed because the framework hands a
     * callback to a handler rather than receiving one: an implementation supplied from outside would carry
     * cancellation state that nothing reads. {@link ReturnableCallback} is {@code non-sealed}: the interface
     * is open, and the only implementation under {@code src/main},
     * {@code de.splatgames.aether.weaver.api.callback.CallbackSupport}, is in the same package and module,
     * so a sealed interface would not have forbidden it either.
     *
     * <p>The permitted list is asserted exactly, so a second permitted subtype fails here.
     */
    @Test
    @DisplayName("a callback can only come from the framework")
    void callbackIsSealedToItsOwnPackage() {
        assertThat(Callback.class.isSealed())
                .as("a user-supplied Callback would have nothing to cancel")
                .isTrue();
        assertThat(Callback.class.getPermittedSubclasses())
                .containsExactly(ReturnableCallback.class);
        assertThat(ReturnableCallback.class.isSealed())
                .as("the engine implements this from another module, so it must stay open")
                .isFalse();
    }

    // -------------------------------------------------------------------------------------

    /**
     * Returns the declared default of an annotation element, failing when it has none.
     *
     * @param type    the annotation type declaring the element
     * @param element the element name
     * @return the default value, boxed as {@link java.lang.reflect.Method#getDefaultValue()} returns it
     */
    private static Object defaultOf(final Class<? extends Annotation> type, final String element) {
        final Object value = elementOf(type, element).getDefaultValue();
        assertThat(value).as("%s.%s() must have a default", type.getSimpleName(), element)
                .isNotNull();
        return value;
    }

    /**
     * Returns the {@link Method} that declares an annotation element.
     *
     * <p>A missing element becomes an {@link AssertionError} naming the type and the element, so a renamed
     * element reads as a failure of the case that wanted it rather than as a checked exception.
     *
     * @param type    the annotation type declaring the element
     * @param element the element name
     * @return the element
     */
    private static Method elementOf(final Class<? extends Annotation> type, final String element) {
        try {
            return type.getDeclaredMethod(element);
        } catch (final NoSuchMethodException e) {
            throw new AssertionError(type.getSimpleName() + " has no element '" + element + '\'', e);
        }
    }

    /**
     * Returns the {@link ElementType} list an annotation declares, failing when it declares none.
     *
     * <p>An absent {@link Target} is a failure rather than an empty result: the language treats it as
     * every declaration context, which is never what one of these annotations means.
     *
     * @param type the annotation type to read
     * @return the declared element types, possibly empty
     */
    private static ElementType[] targetsOf(final Class<? extends Annotation> type) {
        final Target target = type.getAnnotation(Target.class);
        assertThat(target).as("%s must declare @Target explicitly", type.getSimpleName())
                .isNotNull();
        return target.value();
    }

    /**
     * Returns the {@code value()} element of a repeatable annotation's container.
     *
     * @param container the container annotation type
     * @return the {@code value()} element
     */
    private static Method valueElementOf(final Class<? extends Annotation> container) {
        try {
            return container.getDeclaredMethod("value");
        } catch (final NoSuchMethodException e) {
            throw new AssertionError(container.getName() + " must declare value()", e);
        }
    }

    /**
     * Returns every annotation type the api module compiled.
     *
     * <p>Class files are found by walking {@code target/classes} relative to the working directory, which
     * is the module directory when the suite runs under Surefire, so the set is the module's own output
     * and nothing from a dependency. Names are sorted before loading, which makes a failure list stable
     * between runs and between platforms.
     *
     * <p>Loading is initialisation-free, so nothing on the surface being scanned is allowed to run. A
     * class file that cannot be loaded is an {@link AssertionError} rather than a skip, since the only way
     * to reach that state is an output directory that does not match the classpath the suite runs on.
     *
     * @return the annotation types, in class file name order
     */
    private static List<Class<?>> annotationTypes() {
        final Path classes = Path.of("target", "classes").toAbsolutePath();
        assertThat(classes).as("the module must be compiled before this test runs").isDirectory();

        final List<Class<?>> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classes)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .map(p -> classes.relativize(p).toString()
                            .replace('/', '.').replace('\\', '.')
                            .replaceAll("\\.class$", ""))
                    .sorted()
                    .forEach(name -> {
                        final Class<?> type;
                        try {
                            type = Class.forName(name, false,
                                    AnnotationSurfaceTest.class.getClassLoader());
                        } catch (final ClassNotFoundException e) {
                            throw new AssertionError("compiled but not loadable: " + name, e);
                        }
                        if (type.isAnnotation()) {
                            found.add(type);
                        }
                    });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(found).as("the scan must find the annotations at all").isNotEmpty();
        return found;
    }
}
