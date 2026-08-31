package de.splatgames.aether.weaver.engine.inject.point;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.ConstantSelector;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.InjectionPoint;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.Site;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.CodeElement;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The registry of the {@link Point} constants, and the implementation of each.
 *
 * <p>Every point here is stateless. The map is static and its values are shared by every caller,
 * including several weaving threads at once, so a point may hold nothing across a call: everything
 * it works on arrives as an argument to {@code find}. The one point with a field, {@code
 * InvokePoint}, holds a {@code boolean} decided at construction that says which of the two
 * identifiers it serves.
 *
 * <h2>What a point reports</h2>
 *
 * <p>A point that takes a target reports {@code AW1043} when it matched nothing, listing every
 * candidate of the kind it was looking for, capped at ten — except {@code THROW}, whose target is
 * optional and whose {@code AW1043} is given an empty candidate list, since a body with no throw at
 * all has nothing to list. The points that take none — {@code HEAD}, {@code RETURN} and
 * {@code TAIL} — report nothing at all, and a body they find no position in is answered with an
 * empty list rather than a diagnostic.
 *
 * <p>{@code AW1103} is reported from this class and from no other, by the invocation point that
 * serves both {@code INVOKE} and {@code INVOKE_AFTER}. It exists because that omission has no other
 * symptom: the selector matched some ordinary calls, the injection succeeded, the accounting is
 * satisfied, and a place the author named is nonetheless not woven. The {@code AW1043} listing
 * cannot cover it, since {@code AW1043} is only raised when nothing matched.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class BuiltInPoints {

    /** How many candidates a diagnostic lists before summarising the rest. */
    private static final int MAX_LISTED = 10;

    /** The points, keyed by identifier; shared by every caller and by every thread. */
    private static final Map<String, InjectionPoint> POINTS = build();

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private BuiltInPoints() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns every built-in point, keyed by identifier.
     *
     * <p>The same map and the same point instances on every call. A caller wanting a registry
     * function can pass {@code all()::get}, which answers {@code null} for anything not built in.
     *
     * @return the built-in points
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public static Map<String, InjectionPoint> all() {
        return POINTS;
    }

    /**
     * Returns what a built-in point does with a target.
     *
     * <p>Answers {@code null} rather than a default for an identifier that is not built in, so a
     * caller checking a contributed or misspelled point can tell "no opinion" from
     * {@code OPTIONAL}.
     *
     * @param point the identifier; must not be {@code null}
     * @return the requirement, or {@code null} when no built-in point carries that identifier
     * @throws NullPointerException if {@code point} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static InjectionPoint.TargetRequirement requirementOf(@NotNull final String point) {
        final InjectionPoint known = POINTS.get(Objects.requireNonNull(point, "point"));
        return known == null ? null : known.targetRequirement();
    }

    /**
     * Builds the registry.
     *
     * <p>One entry per constant of {@link Point}, keyed by the constant's name, which is what a
     * declaration written with a {@link Point} carries. {@code INVOKE} and {@code INVOKE_AFTER}
     * share an implementation and differ only in the kind of site they return.
     *
     * @return the registry, unmodifiable and of unspecified iteration order
     */
    @NotNull
    private static Map<String, InjectionPoint> build() {
        final Map<String, InjectionPoint> points = new LinkedHashMap<>();
        points.put(Point.HEAD.name(), new HeadPoint());
        points.put(Point.RETURN.name(), new ReturnPoint());
        points.put(Point.TAIL.name(), new TailPoint());
        points.put(Point.INVOKE.name(), new InvokePoint(false));
        points.put(Point.INVOKE_AFTER.name(), new InvokePoint(true));
        points.put(Point.FIELD.name(), new FieldPoint());
        points.put(Point.NEW.name(), new NewPoint());
        points.put(Point.CONSTANT.name(), new ConstantPoint());
        points.put(Point.THROW.name(), new ThrowPoint());
        return Map.copyOf(points);
    }

    /**
     * {@code HEAD}: the position at which the method's own code begins.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    static final class HeadPoint implements InjectionPoint {

        /**
         * Returns this point's identifier.
         *
         * @return {@code HEAD}
         */
        @Override
        @NotNull
        public String id() {
            return Point.HEAD.name();
        }

        /**
         * Returns what this point does with a target.
         *
         * @return {@code FORBIDDEN}; the point locates a position and matches nothing
         */
        @Override
        @NotNull
        public TargetRequirement targetRequirement() {
            return TargetRequirement.FORBIDDEN;
        }

        /**
         * Reports whether this point accepts a shift.
         *
         * <p>Only {@code NONE}: one element earlier than the start of a body is not in the body.
         *
         * @param shift the shift the declaration wrote; must not be {@code null}
         * @return {@code true} only for {@code NONE}
         */
        @Override
        public boolean supportsShift(@NotNull final At.Shift shift) {
            return shift == At.Shift.NONE;
        }

        /**
         * Returns the single site at the start of the method's own code.
         *
         * <p>Always exactly one site, and its element is {@code null}: the position is not an
         * instruction that was matched.
         *
         * @param method   the target method; must not be {@code null}
         * @param code     the body to search; must not be {@code null}
         * @param spec     the declaration's point; must not be {@code null}
         * @param reporter unused, since this point cannot fail to find its position
         * @return one site of kind {@code METHOD_ENTRY}
         */
        @Override
        @Unmodifiable
        @NotNull
        public List<Site> find(@NotNull final MethodView method,
                              @NotNull final CodeView code,
                              @NotNull final PointSpec spec,
                              @NotNull final Reporter reporter) {
            final int entry = method.isConstructor()
                    ? afterInitialiserCall(code)
                    : firstInstruction(code);
            return List.of(new Site(entry, Site.Kind.METHOD_ENTRY, null));
        }

        /**
         * Returns the position just after a constructor's own {@code super()} or {@code this()}
         * call.
         *
         * <p>This is what makes {@code HEAD} usable by an instance handler in a constructor: before
         * the delegating call, {@code this} is not yet initialised. Finding it means skipping the
         * constructor calls that belong to objects the arguments allocate, which the depth counter
         * does by pairing each {@code <init>} with a preceding {@code new}; the one at depth zero
         * is the delegation.
         *
         * <p>Falls back to the first instruction for a constructor with no delegating call —
         * {@code java.lang.Object}'s own, whose body is a single {@code return}.
         *
         * @param code the constructor's body; must not be {@code null}
         * @return the index after the delegating call
         */
        @Contract(pure = true)
        private static int afterInitialiserCall(@NotNull final CodeView code) {
            final List<CodeElement> elements = code.elements();
            int depth = 0;
            for (int i = 0; i < elements.size(); i++) {
                final CodeElement element = elements.get(i);
                if (element instanceof NewObjectInstruction) {
                    depth++;
                } else if (element instanceof InvokeInstruction invoke
                        && invoke.opcode() == Opcode.INVOKESPECIAL
                        && "<init>".equals(invoke.name().stringValue())) {
                    if (depth == 0) {
                        return i + 1;
                    }
                    depth--;
                }
            }
            return firstInstruction(code);
        }

        /**
         * Returns the index of the body's first instruction.
         *
         * <p>Not index zero. A parsed body's element list holds pseudo-elements as well as
         * instructions, and measured on JDK 25 a body compiled with debug information begins with
         * its {@code LocalVariable} entries, a label and a line number.
         *
         * @param code the body; must not be {@code null}
         * @return the index of the first instruction, or {@code 0} for a body with none
         */
        @Contract(pure = true)
        private static int firstInstruction(@NotNull final CodeView code) {
            final List<CodeElement> elements = code.elements();
            for (int i = 0; i < elements.size(); i++) {
                if (elements.get(i) instanceof Instruction) {
                    return i;
                }
            }
            return 0;
        }
    }

    /**
     * {@code RETURN}: every return instruction in the body.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    static final class ReturnPoint implements InjectionPoint {

        /**
         * Returns this point's identifier.
         *
         * @return {@code RETURN}
         */
        @Override
        @NotNull
        public String id() {
            return Point.RETURN.name();
        }

        /**
         * Returns what this point does with a target.
         *
         * @return {@code FORBIDDEN}; the point locates positions and matches nothing
         */
        @Override
        @NotNull
        public TargetRequirement targetRequirement() {
            return TargetRequirement.FORBIDDEN;
        }

        /**
         * Returns a site at every return instruction.
         *
         * <p>Reports nothing when there is none. A method whose body cannot complete normally is
         * legitimate, and a declaration that matched no return is accounted for by the declaration
         * itself rather than by this point.
         *
         * @param method   the target method; must not be {@code null}
         * @param code     the body to search; must not be {@code null}
         * @param spec     the declaration's point; must not be {@code null}
         * @param reporter unused; this point reports nothing
         * @return one site per return, in body order, of kind {@code METHOD_EXIT}
         */
        @Override
        @Unmodifiable
        @NotNull
        public List<Site> find(@NotNull final MethodView method,
                              @NotNull final CodeView code,
                              @NotNull final PointSpec spec,
                              @NotNull final Reporter reporter) {
            return returns(code);
        }

        /**
         * Returns a site at every return instruction of the body.
         *
         * <p>Shared with {@code TailPoint}, which takes the last of them, so the two cannot come to
         * different conclusions about what a return is. An {@code athrow} is not one: a method that
         * leaves by throwing does not pass through a return, and {@code THROW} is the point for
         * that.
         *
         * @param code the body to search; must not be {@code null}
         * @return one site per return, in body order, each carrying the return instruction
         */
        @Contract(pure = true)
        @NotNull
        static List<Site> returns(@NotNull final CodeView code) {
            final List<Site> sites = new ArrayList<>();
            final List<CodeElement> elements = code.elements();
            for (int i = 0; i < elements.size(); i++) {
                if (elements.get(i) instanceof ReturnInstruction returning) {
                    sites.add(new Site(i, Site.Kind.METHOD_EXIT, returning));
                }
            }
            return List.copyOf(sites);
        }
    }

    /**
     * {@code TAIL}: the last return instruction in the body.
     *
     * <p>The last one in body order, which is not the same as "every exit" and not the same as "the
     * exit that runs". A method with an early return has more than one, and only the textually last
     * is matched here.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    static final class TailPoint implements InjectionPoint {

        /**
         * Returns this point's identifier.
         *
         * @return {@code TAIL}
         */
        @Override
        @NotNull
        public String id() {
            return Point.TAIL.name();
        }

        /**
         * Returns what this point does with a target.
         *
         * @return {@code FORBIDDEN}; the point locates a position and matches nothing
         */
        @Override
        @NotNull
        public TargetRequirement targetRequirement() {
            return TargetRequirement.FORBIDDEN;
        }

        /**
         * Returns the site at the last return instruction.
         *
         * @param method   the target method; must not be {@code null}
         * @param code     the body to search; must not be {@code null}
         * @param spec     the declaration's point; must not be {@code null}
         * @param reporter unused; this point reports nothing
         * @return one site, or an empty list for a body with no return
         */
        @Override
        @Unmodifiable
        @NotNull
        public List<Site> find(@NotNull final MethodView method,
                              @NotNull final CodeView code,
                              @NotNull final PointSpec spec,
                              @NotNull final Reporter reporter) {
            final List<Site> all = ReturnPoint.returns(code);
            return all.isEmpty() ? List.of() : List.of(all.getLast());
        }
    }

    /**
     * {@code INVOKE} and {@code INVOKE_AFTER}: a call the target makes, matched on one side or the
     * other.
     *
     * <p>One implementation registered twice. The two differ only in the kind of site they return,
     * which is what decides the side; they match exactly the same instructions.
     *
     * <p>Ordinary calls only. An {@code invokedynamic} is not one, and a selector that names
     * something reachable through one is reported as {@code AW1103} rather than matched.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    static final class InvokePoint implements InjectionPoint {

        /** Whether this instance serves {@code INVOKE_AFTER} rather than {@code INVOKE}. */
        private final boolean after;

        /**
         * Creates the point for one of the two sides.
         *
         * @param after {@code true} for {@code INVOKE_AFTER}, {@code false} for {@code INVOKE}
         */
        InvokePoint(final boolean after) {
            this.after = after;
        }

        /**
         * Returns this point's identifier.
         *
         * @return {@code INVOKE_AFTER} or {@code INVOKE}, according to which side this instance
         *         serves
         */
        @Override
        @NotNull
        public String id() {
            return this.after ? Point.INVOKE_AFTER.name() : Point.INVOKE.name();
        }

        /**
         * Returns what this point does with a target.
         *
         * @return {@code REQUIRED}; without one the point would match every call in the body
         */
        @Override
        @NotNull
        public TargetRequirement targetRequirement() {
            return TargetRequirement.REQUIRED;
        }

        /**
         * Returns a site at every ordinary call the target matches.
         *
         * <p>Walks the body once, collecting three things at the same time: the matches, every call
         * seen, for the listing under {@code AW1043}, and every method handle in an
         * {@code invokedynamic}'s bootstrap arguments that the target also names, for
         * {@code AW1103}.
         *
         * <p>The two reports are independent. A selector that matches nothing and also names a
         * lambda's implementation method produces both, and the {@code AW1103} detail then says
         * that nothing else matched.
         *
         * @param method   the target method, named in the diagnostics; must not be {@code null}
         * @param code     the body to search; must not be {@code null}
         * @param spec     the declaration's point, carrying the target; must not be {@code null}
         * @param reporter where the diagnostics go; must not be {@code null}
         * @return one site per matching call, in body order, of the kind this instance serves
         */
        @Override
        @Unmodifiable
        @NotNull
        public List<Site> find(@NotNull final MethodView method,
                              @NotNull final CodeView code,
                              @NotNull final PointSpec spec,
                              @NotNull final Reporter reporter) {
            final List<Site> sites = new ArrayList<>();
            final List<String> seen = new ArrayList<>();
            final List<String> hidden = new ArrayList<>();
            boolean sawIndy = false;

            final List<CodeElement> elements = code.elements();
            for (int i = 0; i < elements.size(); i++) {
                final CodeElement element = elements.get(i);
                if (element instanceof final InvokeDynamicInstruction indy) {
                    sawIndy = true;
                    hiddenBy(indy, spec, hidden);
                    continue;
                }
                if (!(element instanceof InvokeInstruction invoke)) {
                    continue;
                }
                final String described = describe(invoke);
                seen.add(described);
                if (matches(described, invoke, spec)) {
                    sites.add(new Site(i,
                            this.after ? Site.Kind.AFTER_ELEMENT : Site.Kind.BEFORE_ELEMENT,
                            invoke));
                }
            }
            if (sites.isEmpty()) {
                reportNothingMatched(reporter, method, spec, "invocation", seen, sawIndy);
            }
            if (!hidden.isEmpty()) {
                reportHiddenByIndy(reporter, method, spec, hidden, sites.size());
            }
            return List.copyOf(sites);
        }

        /**
         * Collects the method handles in one {@code invokedynamic}'s bootstrap arguments that the
         * target names.
         *
         * <p>The instruction's own name and type belong to the functional interface — {@code get}
         * for a {@code Supplier} — while the method the author wrote travels as a
         * {@link DirectMethodHandleDesc} among the bootstrap arguments. Matching the instruction
         * would name something nobody wrote; matching the handles names the method the selector
         * meant.
         *
         * <p>A bootstrap argument that is not a direct method handle is skipped: a string
         * concatenation's recipe and a lambda's two method types carry no method.
         *
         * @param indy   the instruction to look inside; must not be {@code null}
         * @param spec   the declaration's point, carrying the target; must not be {@code null}
         * @param hidden the list to add the matching renderings to; must not be {@code null}
         */
        private static void hiddenBy(@NotNull final InvokeDynamicInstruction indy,
                                     @NotNull final PointSpec spec,
                                     @NotNull final List<String> hidden) {
            for (final ConstantDesc argument : indy.bootstrapArgs()) {
                if (!(argument instanceof final DirectMethodHandleDesc handle)
                        || !(handle.invocationType() instanceof final MethodTypeDesc type)) {
                    continue;
                }
                final String owner = internalNameOf(handle.owner());
                final String described = owner.replace('/', '.') + '.' + handle.methodName()
                        + type.descriptorString();
                if (Targets.matchesInvocation(spec, handle.methodName(), owner, type, described)) {
                    hidden.add(described);
                }
            }
        }

        /**
         * Returns a handle owner as an internal name.
         *
         * <p>A descriptor that is not a class descriptor — an array, which can own {@code clone()}
         * — is returned as it is, which is the form an owner takes in a class file anyway.
         *
         * @param type the owner; must not be {@code null}
         * @return the internal name, or the descriptor unchanged where it names no class
         */
        @NotNull
        private static String internalNameOf(@NotNull final ClassDesc type) {
            final String descriptor = type.descriptorString();
            return descriptor.startsWith("L") && descriptor.endsWith(";")
                    ? descriptor.substring(1, descriptor.length() - 1)
                    : descriptor;
        }

        /**
         * Reports {@code AW1103}, naming what the selector reached through an
         * {@code invokedynamic}.
         *
         * <p>The detail lines list the hidden methods, capped at ten, and then say whether anything
         * else did match.
         *
         * @param reporter where the diagnostic goes; must not be {@code null}
         * @param method   the target method; must not be {@code null}
         * @param spec     the declaration's point; must not be {@code null}
         * @param hidden   the renderings of what was reached through an {@code invokedynamic}; must
         *                 not be {@code null} or empty
         * @param matched  how many ordinary calls did match
         */
        private static void reportHiddenByIndy(@NotNull final Reporter reporter,
                                               @NotNull final MethodView method,
                                               @NotNull final PointSpec spec,
                                               @NotNull final List<String> hidden,
                                               final int matched) {
            final de.splatgames.aether.weaver.api.diagnostic.Diagnostic.Builder builder =
                    de.splatgames.aether.weaver.api.diagnostic.Diagnostic.builder(
                                    de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode
                                            .SELECTOR_MATCHES_INVOKEDYNAMIC)
                            .message(spec.point() + " target=" + spec.rawTarget()
                                    + " also names something reached through an invokedynamic in "
                                    + method.describe());
            hidden.stream().limit(MAX_LISTED).forEach(one -> builder.detail("  " + one));
            builder.detail(matched == 0
                    ? "nothing else matched, so this injection attaches to no call at all"
                    : matched + " ordinary call" + (matched == 1 ? "" : "s")
                            + " did match and were woven");
            reporter.report(builder
                    .remedy("a lambda, a method reference and string concatenation are "
                            + "invokedynamic instructions, and INVOKE matches ordinary calls only. "
                            + "The method behind them is invoked by the JVM rather than by this "
                            + "method, so inject into that method directly")
                    .build());
        }

        /**
         * Returns a call rendered the way a target in text form is written.
         *
         * <p>Owner in binary form, a dot, the name, then the descriptor with no separator, which is
         * what a full target is compared against literally. The same rendering is what a listing
         * under {@code AW1043} shows, so an author who copies a line out of the listing has a
         * target that matches.
         *
         * @param invoke the call; must not be {@code null}
         * @return the rendering, for example {@code com.acme.Gateway.send(Ljava/lang/String;)V}
         */
        @Contract(pure = true)
        @NotNull
        private static String describe(@NotNull final InvokeInstruction invoke) {
            return invoke.owner().asInternalName().replace('/', '.')
                    + '.' + invoke.name().stringValue() + invoke.type().stringValue();
        }

        /**
         * Reports whether the declaration's target names this call.
         *
         * @param described the call's rendering, for the text form; must not be {@code null}
         * @param invoke    the call; must not be {@code null}
         * @param spec      the declaration's point; must not be {@code null}
         * @return {@code true} when the target names it
         */
        @Contract(pure = true)
        private static boolean matches(@NotNull final String described,
                                       @NotNull final InvokeInstruction invoke,
                                       @NotNull final PointSpec spec) {
            return Targets.matchesInvocation(spec, invoke.name().stringValue(),
                    invoke.owner().asInternalName(), invoke.typeSymbol(), described);
        }
    }

    /**
     * {@code FIELD}: a field the target reads or writes.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    static final class FieldPoint implements InjectionPoint {

        /**
         * Returns this point's identifier.
         *
         * @return {@code FIELD}
         */
        @Override
        @NotNull
        public String id() {
            return Point.FIELD.name();
        }

        /**
         * Returns what this point does with a target.
         *
         * @return {@code REQUIRED}; without one the point would match every field access in the
         *         body
         */
        @Override
        @NotNull
        public TargetRequirement targetRequirement() {
            return TargetRequirement.REQUIRED;
        }

        /**
         * Returns a site at every field access the declaration matches.
         *
         * <p>Two filters, and both have to pass: the access kind from {@code At.access}, and the
         * target. The listing under {@code AW1043} names every access with its kind in brackets, so
         * a declaration that matched nothing because it asked for a write of a field that is only
         * read shows exactly that.
         *
         * @param method   the target method, named in the diagnostic; must not be {@code null}
         * @param code     the body to search; must not be {@code null}
         * @param spec     the declaration's point, carrying the target and the access kind; must
         *                 not be {@code null}
         * @param reporter where the diagnostic goes; must not be {@code null}
         * @return one site per matching access, in body order, of kind {@code BEFORE_ELEMENT}
         */
        @Override
        @Unmodifiable
        @NotNull
        public List<Site> find(@NotNull final MethodView method,
                              @NotNull final CodeView code,
                              @NotNull final PointSpec spec,
                              @NotNull final Reporter reporter) {
            final List<Site> sites = new ArrayList<>();
            final List<String> seen = new ArrayList<>();

            final List<CodeElement> elements = code.elements();
            for (int i = 0; i < elements.size(); i++) {
                if (!(elements.get(i) instanceof FieldInstruction access)) {
                    continue;
                }
                final String described = access.owner().asInternalName().replace('/', '.')
                        + '.' + access.name().stringValue();
                seen.add(described + "  [" + accessKindOf(access) + ']');
                if (accessMatches(spec.access(), access)
                        && Targets.matchesFieldAccess(spec, access.name().stringValue(),
                                access.owner().asInternalName(), access.typeSymbol(), described)) {
                    sites.add(new Site(i, Site.Kind.BEFORE_ELEMENT, access));
                }
            }
            if (sites.isEmpty()) {
                reportNothingMatched(reporter, method, spec, "field access", seen, false);
            }
            return List.copyOf(sites);
        }

        /**
         * Reports whether an access is of the requested kind.
         *
         * <p>Static and instance access are separate kinds rather than a modifier on one, so
         * {@code GET} does not match a {@code getstatic}. {@code ANY} is the only value that
         * matches both.
         *
         * @param requested the kind the declaration asked for; must not be {@code null}
         * @param access    the instruction; must not be {@code null}
         * @return {@code true} when the opcode is of the requested kind
         */
        @Contract(pure = true)
        private static boolean accessMatches(@NotNull final At.Access requested,
                                             @NotNull final FieldInstruction access) {
            return switch (requested) {
                case ANY -> true;
                case GET -> access.opcode() == Opcode.GETFIELD;
                case PUT -> access.opcode() == Opcode.PUTFIELD;
                case STATIC_GET -> access.opcode() == Opcode.GETSTATIC;
                case STATIC_PUT -> access.opcode() == Opcode.PUTSTATIC;
            };
        }

        /**
         * Returns the access kind of an instruction, spelled as {@code At.Access} spells it.
         *
         * <p>For the listing under {@code AW1043}, so that what an author reads there is the value
         * they would write. The {@code default} prints the opcode's own name and is unreachable for
         * a {@link FieldInstruction}, which has only the four opcodes.
         *
         * @param access the instruction; must not be {@code null}
         * @return the kind's name
         */
        @Contract(pure = true)
        @NotNull
        private static String accessKindOf(@NotNull final FieldInstruction access) {
            return switch (access.opcode()) {
                case GETFIELD -> "GET";
                case PUTFIELD -> "PUT";
                case GETSTATIC -> "STATIC_GET";
                case PUTSTATIC -> "STATIC_PUT";
                default -> access.opcode().name();
            };
        }
    }

    /**
     * {@code NEW}: an allocation the target performs, matched at the {@code new} itself.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    static final class NewPoint implements InjectionPoint {

        /**
         * Returns this point's identifier.
         *
         * @return {@code NEW}
         */
        @Override
        @NotNull
        public String id() {
            return Point.NEW.name();
        }

        /**
         * Returns what this point does with a target.
         *
         * @return {@code REQUIRED}; without one the point would match every allocation in the body
         */
        @Override
        @NotNull
        public TargetRequirement targetRequirement() {
            return TargetRequirement.REQUIRED;
        }

        /**
         * Returns a site at every allocation of the named type.
         *
         * <p>The target names a type, not a member, so it is matched against the allocated type's
         * binary name and a parsed selector is not consulted at all. A simple name matches any
         * package.
         *
         * <p>The site is at the {@code new}, before the object exists and before its constructor
         * runs; the arguments have not been evaluated either.
         *
         * @param method   the target method, named in the diagnostic; must not be {@code null}
         * @param code     the body to search; must not be {@code null}
         * @param spec     the declaration's point, carrying the type; must not be {@code null}
         * @param reporter where the diagnostic goes; must not be {@code null}
         * @return one site per matching allocation, in body order, of kind {@code BEFORE_ELEMENT}
         */
        @Override
        @Unmodifiable
        @NotNull
        public List<Site> find(@NotNull final MethodView method,
                              @NotNull final CodeView code,
                              @NotNull final PointSpec spec,
                              @NotNull final Reporter reporter) {
            final List<Site> sites = new ArrayList<>();
            final List<String> seen = new ArrayList<>();

            final List<CodeElement> elements = code.elements();
            for (int i = 0; i < elements.size(); i++) {
                if (!(elements.get(i) instanceof NewObjectInstruction created)) {
                    continue;
                }
                final String type = created.className().asInternalName().replace('/', '.');
                seen.add(type);
                if (Targets.matchesType(spec, type)) {
                    sites.add(new Site(i, Site.Kind.BEFORE_ELEMENT, created));
                }
            }
            if (sites.isEmpty()) {
                reportNothingMatched(reporter, method, spec, "instantiation", seen, false);
            }
            return List.copyOf(sites);
        }
    }

    /**
     * {@code CONSTANT}: a constant the target loads.
     *
     * <p>A small integer is carried directly by the opcode rather than by {@code ldc}, so a point
     * that looked only for {@code ldc} would miss most of the integers a method loads.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    static final class ConstantPoint implements InjectionPoint {

        /**
         * Returns this point's identifier.
         *
         * @return {@code CONSTANT}
         */
        @Override
        @NotNull
        public String id() {
            return Point.CONSTANT.name();
        }

        /**
         * Returns what this point does with a target.
         *
         * @return {@code OPTIONAL}; without one every constant in the body matches
         */
        @Override
        @NotNull
        public TargetRequirement targetRequirement() {
            return TargetRequirement.OPTIONAL;
        }

        /**
         * Returns a site at every constant the declaration matches.
         *
         * <p>The listing under {@code AW1043} prints each candidate's value, which is what a target
         * in text form is compared against.
         *
         * @param method   the target method, named in the diagnostic; must not be {@code null}
         * @param code     the body to search; must not be {@code null}
         * @param spec     the declaration's point; must not be {@code null}
         * @param reporter where the diagnostic goes; must not be {@code null}
         * @return one site per matching constant, in body order, of kind {@code BEFORE_ELEMENT}
         */
        @Override
        @Unmodifiable
        @NotNull
        public List<Site> find(@NotNull final MethodView method,
                              @NotNull final CodeView code,
                              @NotNull final PointSpec spec,
                              @NotNull final Reporter reporter) {
            final List<Site> sites = new ArrayList<>();
            final List<String> seen = new ArrayList<>();

            final List<CodeElement> elements = code.elements();
            for (int i = 0; i < elements.size(); i++) {
                // Every ConstantInstruction subtype: intrinsic, argument-carrying and ldc alike.
                if (!(elements.get(i) instanceof ConstantInstruction constant)) {
                    continue;
                }
                final String described = String.valueOf(constant.constantValue());
                seen.add(described);
                if (matches(spec, constant.constantValue(), described)) {
                    sites.add(new Site(i, Site.Kind.BEFORE_ELEMENT, constant));
                }
            }
            if (sites.isEmpty()) {
                reportNothingMatched(reporter, method, spec, "constant", seen, false);
            }
            return List.copyOf(sites);
        }

        /**
         * Reports whether the declaration's target names this constant.
         *
         * <p>Three cases, and the first two are the ones that matter. With no target, everything
         * matches. With a parsed {@link ConstantSelector}, the selector's <em>value</em> is
         * compared with the instruction's, so the comparison is by value and not by spelling: a
         * selector whose value is absent stands for {@code null} and matches the {@code aconst_null}
         * the class file records as {@link ConstantDescs#NULL}.
         *
         * <p>Anything else — including a target parsed as some other kind of selector, which is
         * what the grammar's disambiguation makes of an unquoted {@code kind:value} — falls back to
         * comparing text: the target with everything up to its first colon removed, against the
         * value's own rendering.
         *
         * @param spec      the declaration's point; must not be {@code null}
         * @param value     the constant the instruction loads, {@code null} where it has none
         * @param described the value's rendering, for the text form; must not be {@code null}
         * @return {@code true} when the target is absent or names this constant
         */
        @Contract(pure = true)
        private static boolean matches(@NotNull final PointSpec spec,
                                       final @Nullable ConstantDesc value,
                                       @NotNull final String described) {
            if (!spec.hasTarget()) {
                return true;
            }
            if (spec.hasSelector() && spec.target() instanceof final ConstantSelector constant) {
                return constant.value()
                        .map(expected -> expected.equals(value))
                        .orElseGet(() -> ConstantDescs.NULL.equals(value));
            }
            return described.equals(stripPrefix(spec.rawTarget()));
        }

        /**
         * Returns a target with its kind prefix removed.
         *
         * <p>Everything up to and including the first colon goes, so {@code int:7} becomes
         * {@code 7}. A target with no colon is returned whole.
         *
         * @param target the target as written, or {@code null}
         * @return the part after the first colon, or the empty string for a {@code null} target
         */
        @Contract(pure = true)
        @NotNull
        private static String stripPrefix(@Nullable final String target) {
            if (target == null) {
                return "";
            }
            final int colon = target.indexOf(':');
            return colon < 0 ? target : target.substring(colon + 1);
        }
    }

    /**
     * {@code THROW}: an {@code athrow} the target performs.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    static final class ThrowPoint implements InjectionPoint {

        /**
         * Returns this point's identifier.
         *
         * @return {@code THROW}
         */
        @Override
        @NotNull
        public String id() {
            return Point.THROW.name();
        }

        /**
         * Returns what this point does with a target.
         *
         * <p>{@code OPTIONAL} rather than {@code FORBIDDEN}, so a target written here is accepted
         * and then ignored rather than refused.
         *
         * @return {@code OPTIONAL}
         */
        @Override
        @NotNull
        public TargetRequirement targetRequirement() {
            return TargetRequirement.OPTIONAL;
        }

        /**
         * Returns a site at every {@code athrow} in the body.
         *
         * <p>Neither {@code Targets} nor the declaration's target is consulted at all, which is
         * what the {@code OPTIONAL} requirement leaves room for: every throw matches, so a
         * declaration naming an exception type matches more sites than it describes.
         *
         * <p>The {@code AW1043} report is given an empty candidate list, so it says that the body
         * contains no throw rather than listing throws that did not match.
         *
         * @param method   the target method, named in the diagnostic; must not be {@code null}
         * @param code     the body to search; must not be {@code null}
         * @param spec     the declaration's point; must not be {@code null}
         * @param reporter where the diagnostic goes; must not be {@code null}
         * @return one site per throw, in body order, of kind {@code BEFORE_ELEMENT}
         */
        @Override
        @Unmodifiable
        @NotNull
        public List<Site> find(@NotNull final MethodView method,
                              @NotNull final CodeView code,
                              @NotNull final PointSpec spec,
                              @NotNull final Reporter reporter) {
            final List<Site> sites = new ArrayList<>();
            final List<CodeElement> elements = code.elements();
            for (int i = 0; i < elements.size(); i++) {
                if (elements.get(i) instanceof ThrowInstruction thrown) {
                    sites.add(new Site(i, Site.Kind.BEFORE_ELEMENT, thrown));
                }
            }
            if (sites.isEmpty()) {
                reportNothingMatched(reporter, method, spec, "throw", List.of(), false);
            }
            return List.copyOf(sites);
        }
    }

    /**
     * Reports {@code AW1043} for a point that matched nothing, listing what was there instead.
     *
     * <p>The listing is the substance of the report: a declaration that matches nothing usually
     * names something close to what the body contains, and the difference is only visible with both
     * side by side. Candidates are capped at ten, with a count of the remainder. A body with no
     * candidate of the kind at all is a different mistake and says so instead of listing nothing.
     *
     * <p>Two notes are added where they apply, covering a case the listing alone does not explain:
     * that {@code invokedynamic} instructions were passed over, and that the selector carries a
     * parameter list.
     *
     * @param reporter where the diagnostic goes; must not be {@code null}
     * @param method   the target method; must not be {@code null}
     * @param spec     the declaration's point; must not be {@code null}
     * @param what     the kind of thing that was searched for, as the message names it; must not be
     *                 {@code null}
     * @param seen     the renderings of every candidate of that kind, in body order; must not be
     *                 {@code null}
     * @param sawIndy  whether an {@code invokedynamic} was passed over during the search
     * @throws NullPointerException if {@code reporter} is {@code null}
     */
    private static void reportNothingMatched(@NotNull final Reporter reporter,
                                             @NotNull final MethodView method,
                                             @NotNull final PointSpec spec,
                                             @NotNull final String what,
                                             @NotNull final List<String> seen,
                                             final boolean sawIndy) {
        Objects.requireNonNull(reporter, "reporter");
        final de.splatgames.aether.weaver.api.diagnostic.Diagnostic.Builder builder =
                de.splatgames.aether.weaver.api.diagnostic.Diagnostic.builder(
                                de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode
                                        .NO_INJECTION_POINT_MATCHED)
                        .message("no " + what + " matched " + spec.point()
                                + (spec.hasTarget() ? " target=" + spec.rawTarget() : "")
                                + " in " + method.describe());

        if (seen.isEmpty()) {
            builder.detail("the method contains no " + what + " at all");
        } else {
            builder.detail("found " + seen.size() + ' ' + what
                    + (seen.size() == 1 ? "" : "s") + " in " + method.describe() + ':');
            seen.stream().limit(MAX_LISTED).forEach(candidate -> builder.detail("  " + candidate));
            if (seen.size() > MAX_LISTED) {
                builder.detail("  ... and " + (seen.size() - MAX_LISTED) + " more");
            }
        }
        if (sawIndy) {
            builder.detail("note: invokedynamic instructions were skipped — lambdas, method "
                    + "references and string concatenation are not INVOKE sites");
        }
        // The listing above prints every candidate with its descriptor, so a signature mismatch
        // is visible in it — but only to a reader who already knows that a parameter list narrows.
        // Saying so is the difference between reading the listing and scrolling past it.
        if (spec.hasSelector() && Targets.constrainsSignature(spec.target())) {
            builder.detail("note: the selector names a signature, so a member of the same name "
                    + "with different parameters does not match; drop the parameter list to match "
                    + "any signature");
        }
        reporter.report(builder
                .remedy("check the target against the listing above; a name-only selector such as "
                        + "'#send' matches any owner, and a slice narrows where the search runs")
                .build());
    }
}
