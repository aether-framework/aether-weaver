package de.splatgames.aether.weaver.api.spi;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Slice;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.PointSpec;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * Decides which positions in a method body a declaration attaches to.
 *
 * <p>An injection point is the implementation behind an {@code @At} identifier. {@code HEAD},
 * {@code RETURN} and {@code INVOKE} are points, and so is every {@code namespace:NAME} a plugin
 * contributes. Its whole job is to look at a body and answer with positions; what is emitted at
 * those positions is an {@link Injector}'s business, and a point that tries to decide both has
 * mistaken which half of the SPI it is in.
 *
 * <p>Implemented by plugins and called by the engine. A point is reached through the
 * {@link InjectionPointFactory} that registers its identifiers.
 *
 * <h2>What the engine does around a call to {@link #find}</h2>
 *
 * <p>Resolution is a pipeline, and a point occupies one stage of it. In order, for one
 * {@code @At} of one declaration against one target method:
 *
 * <ol>
 *   <li><b>{@link #targetRequirement()} is checked.</b> A mismatch is reported as {@code AW1043}
 *       and {@link #find} is not called.
 *   <li><b>{@link #supportsShift(At.Shift)} is checked.</b> A refused shift is reported as
 *       {@code AW1102} and {@link #find} is not called.
 *   <li><b>The slice is resolved.</b> Each bound is itself a point specification, resolved by
 *       calling {@link #find} on the point that bound names; a bound matching nothing is reported
 *       as {@code AW1120} or {@code AW1121}, and a slice that ends before it begins as
 *       {@code AW1122}.
 *   <li><b>{@link #find} is called</b>, with a view of the slice rather than of the whole body
 *       where a slice was named.
 *   <li><b>The indices are translated back</b> into the whole body: the slice's offset is added,
 *       and a site of kind {@link Site.Kind#AFTER_ELEMENT} is moved one element on, so that every
 *       injector can emit before the index it is handed.
 *   <li><b>The ordinal is applied</b>, as a position in the returned list. An ordinal past the end
 *       is reported as {@code AW1110}.
 *   <li><b>The shift is applied.</b> A shift that leaves the searched range is reported as
 *       {@code AW1111}, and a {@code BY} offset of more than four elements draws the warning
 *       {@code AW1112}.
 *   <li><b>Positions nothing may be injected at are dropped</b>, which the earlier stages happily
 *       find — but only for {@link InjectorKind#INJECT}: an operation-replacing kind ({@link
 *       InjectorKind#REDIRECT} or {@link InjectorKind#WRAP}) narrows to positions that name an
 *       operation instead, and every other kind skips this step entirely and keeps every position
 *       it found. For an injection, before a constructor's {@code super()} call is {@code AW1026},
 *       but only for a non-static handler — a static one has nothing to do with an unconstructed
 *       instance and is kept; inside the window where a {@code new} has not yet been initialised is
 *       {@code AW1105}; and unreachable code is the warning {@code AW1130}.
 * </ol>
 *
 * <p>Two consequences worth reading twice. An ordinal counts within the slice, because the point
 * never saw the rest of the method. And a point is asked for every match it can find, not for the
 * one the declaration will end up using — narrowing is the engine's job, and a point that applies
 * the ordinal itself applies it twice.
 *
 * <h2>What {@link #find} owes</h2>
 *
 * <ul>
 *   <li><b>Indices into the view it was handed.</b> Not bytecode offsets, not instruction ordinals,
 *       and not indices into the whole method when a slice narrowed it.
 *   <li><b>An order the ordinal can count in</b>, which for every built-in point is ascending
 *       position.
 *   <li><b>An empty list rather than an exception</b> when nothing matches. Falling short of a
 *       declaration's {@code require} is accounted for afterwards and reported as {@code AW1043},
 *       with the point's own diagnostic beside it if it raised one.
 *   <li><b>No changes to anything.</b> A point may be called several times for one method — once
 *       per {@code @At}, and again for every slice bound naming it — and the body it is handed is
 *       a snapshot that is not the object being rewritten.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #find}, {@link #targetRequirement()} and {@link #supportsShift(At.Shift)} are called on
 * the thread rewriting the method they were asked about, which under the load-time driver is the
 * thread loading the class that method belongs to. {@code
 * WeavingClassLoader.registerAsParallelCapable()} lets more than one class load, and therefore be
 * woven, at the same time, and each of those weaves reaches the point through the same
 * {@link InjectionPointFactory}. A factory that hands out one shared instance rather than building
 * a fresh one per call — the ordinary case, since {@link InjectionPointFactory#create(String)}
 * caches nothing itself — must therefore expect these three methods to be called concurrently from
 * different threads, one per class being woven, and must not keep unsynchronized mutable state
 * across them.
 *
 * <h2>Throwing</h2>
 *
 * <p>A contributed point that throws out of any of these methods is contained, with one
 * exception: a {@code VirtualMachineError} is rethrown rather than caught, on the theory that the
 * JVM itself is compromised and reporting "a plugin threw" would misstate what is happening. Every
 * other throwable is reported as {@code AW3116} naming the point, and the rest of the run
 * continues. What is lost is
 * that one {@code @At}'s matches, not the whole declaration: a declaration writing two {@code @At}s
 * whose first throws still accumulates whatever its second one found and is bound and emitted at
 * those positions. Containment covers a point reached directly from a declaration's own {@code @At}
 * — every identifier that carries a namespace, since the built-in namespace is reserved for the
 * framework and a plugin claiming it is refused — but not a contributed point reached only as a
 * slice bound: resolving a {@link Slice} bound runs inside the same resolution that resolves the
 * surrounding {@code @At}, so a built-in {@code @At(Point.HEAD)} whose
 * slice names a contributed bound calls that bound's point without this containment, and a throw
 * from it is not caught here. Reporting a user's mistake through the {@link Reporter} and returning
 * an empty list is the intended way to fail; an exception costs at least that {@code @At} its
 * matches and tells the author nothing they can act on.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class AfterLoggingPoint implements InjectionPoint {
 *
 *     private static final PluginDiagnosticId NO_LOGGING = new PluginDiagnosticId(
 *             "acme", "AX0001", Severity.WARNING, DiagnosticCode.Category.INJECTION_POINT,
 *             "no logging call was found in the target method");
 *
 *     @Override
 *     public String id() {
 *         return "acme:AFTER_LOGGING";
 *     }
 *
 *     @Override
 *     public TargetRequirement targetRequirement() {
 *         return TargetRequirement.FORBIDDEN;   // the point locates a position by itself
 *     }
 *
 *     @Override
 *     public boolean supportsShift(At.Shift shift) {
 *         return shift != At.Shift.BEFORE;      // before the call is a different point
 *     }
 *
 *     @Override
 *     public List<Site> find(MethodView method, CodeView code, PointSpec spec, Reporter reporter) {
 *         List<Site> sites = new ArrayList<>();
 *         List<CodeElement> elements = code.elements();
 *         for (int at = 0; at < elements.size(); at++) {
 *             if (elements.get(at) instanceof InvokeInstruction invoke
 *                     && invoke.owner().asInternalName().startsWith("org/slf4j/")) {
 *                 sites.add(new Site(at, Site.Kind.AFTER_ELEMENT, invoke));
 *             }
 *         }
 *         if (sites.isEmpty()) {
 *             reporter.report(NO_LOGGING, "no SLF4J call in " + method.describe());
 *         }
 *         return List.copyOf(sites);
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see InjectionPointFactory
 * @see Site
 * @see CodeView
 */
@ApiStatus.OverrideOnly
public interface InjectionPoint {

    /**
     * Returns the identifier this point serves.
     *
     * <p>The same string the factory registered under {@link InjectionPointFactory#ids()}:
     * {@code HEAD} for a built-in, {@code namespace:NAME} for a contributed point. Only the
     * contributed spelling, written {@code @At(custom = "namespace:NAME")}, is also the string a
     * declaration writes; a built-in point is named through {@link At#value()}, whose type is the
     * {@link Point} enum, so a declaration writes {@code @At(Point.HEAD)} rather than the string
     * this method returns. A factory serving several
     * identifiers is told which one was asked for in
     * {@link InjectionPointFactory#create(String)}, and a point that answers for exactly one
     * returns that one.
     *
     * <p>Diagnostics the engine raises about a declaration's point quote
     * {@link PointSpec#point()} — the identifier as the author wrote it, which for a resolved
     * alias is the retired spelling rather than this one.
     *
     * @return the identifier, never blank
     */
    @Contract(pure = true)
    @NotNull
    String id();

    /**
     * Returns whether this point needs the {@code target} of an {@code @At} to do its work.
     *
     * <p>Checked before {@link #find} is called, and a mismatch stops resolution there with
     * {@code AW1043}.
     *
     * @return what this point does with a target; {@link TargetRequirement#OPTIONAL} unless
     *         overridden
     */
    @Contract(pure = true)
    @NotNull
    default TargetRequirement targetRequirement() {
        return TargetRequirement.OPTIONAL;
    }

    /**
     * Returns whether a position this point finds may be moved by the given shift.
     *
     * <p>Asked once per declaration, before {@link #find} is called, with the shift the declaration
     * wrote — including {@link At.Shift#NONE}, so a point that answers {@code false} for everything
     * can never be used. A refused shift is reported as {@code AW1102}.
     *
     * <p>Shifting itself is done by the engine after {@link #find} returns; a point neither
     * performs nor observes it. Refusing one is how a point says that a neighbouring element is not
     * a position it can vouch for — {@code HEAD} accepts only {@link At.Shift#NONE} for exactly
     * that reason, since one element earlier than the start of a body is not in the body.
     *
     * @param shift the shift the declaration wrote; never {@code null}
     * @return {@code true} to allow it; {@code true} for every shift unless overridden
     */
    @Contract(pure = true)
    default boolean supportsShift(@NotNull final At.Shift shift) {
        return true;
    }

    /**
     * Finds every position in the given body that this point matches.
     *
     * <p>Called once per {@code @At} of a declaration per target method, and once more for each
     * slice bound that names this point. A call made for a slice bound is handed
     * {@link Reporter#NOOP}, so a diagnostic reported while resolving a bound is discarded; the
     * engine reports the bound's own failure as {@code AW1120} or {@code AW1121} instead.
     *
     * <p>Matches are returned in the order an ordinal should count them, and each index is a
     * position in {@code code}, which is the slice rather than the whole method where the
     * declaration named one. Returning an empty list is how a point says the method has nothing it
     * recognises; that is not itself an error, and whether it becomes one is decided by the
     * declaration's {@code require} afterwards.
     *
     * @param method   the method being searched, whose {@link MethodView#code()} is the whole body
     *                 even when {@code code} is a slice of it
     * @param code     the elements to search, indices into which are what the returned sites name
     * @param spec     the {@code @At} as written, carrying the target selector, the ordinal, the
     *                 shift and any arguments a contributed point defines for itself
     * @param reporter where to report why nothing matched, or what matched and had to be skipped
     * @return the matched positions, in ordinal order; empty when nothing matched
     */
    @NotNull
    @Unmodifiable
    List<Site> find(@NotNull MethodView method,
                    @NotNull CodeView code,
                    @NotNull PointSpec spec,
                    @NotNull Reporter reporter);

    /**
     * What a point does with the {@code target} selector of an {@code @At}.
     *
     * <p>The distinction is between a point that matches something — a call, a field access — and
     * one that locates a position on its own. Both mistakes are reported as {@code AW1043} before
     * the point is asked to search.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    enum TargetRequirement {

        /**
         * The point cannot work without a target.
         *
         * <p>A declaration that gives none is refused with {@code AW1043}, whose remedy shows the
         * two spellings a target accepts: a full {@code Gateway.send(Payment)} or the name-only
         * {@code #send}.
         */
        REQUIRED,

        /**
         * The point works with or without a target, and narrows its search when one is given.
         *
         * <p>The default, and what a point that matches instructions of a kind usually wants.
         */
        OPTIONAL,

        /**
         * The point locates a position by itself and a target means nothing to it.
         *
         * <p>A declaration that gives one is refused with {@code AW1043} rather than having it
         * silently ignored, because a target that does nothing looks exactly like a target that
         * failed to narrow anything.
         */
        FORBIDDEN
    }
}
