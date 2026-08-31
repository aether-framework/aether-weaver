package de.splatgames.aether.weaver.api.spi;

import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.constant.ClassDesc;

/**
 * One declaration paired with one class it is to be applied to.
 *
 * <p>A plan entry is the unit the engine works in once weave classes have been read. A weave naming
 * three targets and declaring two handlers produces six entries, one per pair, and each of them
 * carries the whole declaration alongside the target it is bound to. Nothing here has been resolved
 * against the target's bytes: the selector has been parsed but not matched, the points name searches
 * that have not been run, and every claim the declaration makes can still turn out to be false of
 * this particular target.
 *
 * <p>An entry reaches a plugin in three places — {@link PlanView#entries()} and
 * {@link PlanView#entriesFor(String)} on the plan a {@link PluginEvent.Prepared} carries,
 * {@link InjectionContext#entry()} while an injector emits, and the {@code entry} argument of
 * {@link Injector#validate(PlanEntryView, TargetView, Reporter)}.
 *
 * <h2>Order</h2>
 *
 * <p>Every stage that lists entries — the plan, the report, the fingerprint, the sequence in which
 * emitters are asked at a shared position — sees the same sequence: {@link #priority()} descending,
 * then {@link #weaveClassName()}, then the handler's name, then the handler's descriptor. These
 * tie-breakers do not make the order total: two entries compare equal whenever one weave names the
 * same handler for several targets, when one handler carries two {@code @Inject} declarations, or
 * when one handler carries both an {@code @Inject} and a {@code @Redirect} (or {@code @Wrap}) —
 * nothing refuses that combination — since in each case the two entries share the same weave class
 * and the same handler. Two builds of the same inputs still agree, because the sort that applies
 * this order is stable and the entries are built in the same order every time.
 *
 * <h2>Where the call is emitted, and who declared the handler</h2>
 *
 * <p>{@link #handler()} and {@link #handlerOwner()} answer different questions and disagree in
 * exactly one case. {@link HandlerRef#owner()} is the class that <em>declares</em> the handler;
 * {@link #handlerOwner()} is the class the {@code invoke} instruction names. They differ for an
 * instance weave that dissolves into its target and whose handler is one of the members moved there,
 * because that handler becomes a method of the target. For every other entry — including one that
 * dissolves but whose handler lives in a shared helper class rather than in the weave itself — the
 * two are the same class.
 *
 * <p>Instances are supplied by the engine.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see PlanView
 * @see InjectionContext#entry()
 */
@ApiStatus.NonExtendable
public interface PlanEntryView {

    /**
     * Returns the class this declaration is to be applied to.
     *
     * <p>One of the targets the weave named, as a descriptor. The internal name that keys
     * {@link PlanView#entriesFor(String)} is this descriptor with its leading {@code L} and trailing
     * semicolon removed, so {@code Lcom/acme/Ledger;} is indexed under {@code com/acme/Ledger}.
     *
     * @return the target class
     */
    @Contract(pure = true)
    @NotNull
    ClassDesc target();

    /**
     * Returns the declaration itself.
     *
     * <p>The parsed {@code @Inject}, {@code @Redirect}, {@code @Wrap} or contributed annotation:
     * which kind it is, which method it selects, which positions inside that method it looks for,
     * how many matches it requires and allows, and which group it is accounted against. An injector
     * interested in one kind filters on {@link InjectorSpec#kind()}.
     *
     * @return the declaration
     */
    @Contract(pure = true)
    @NotNull
    InjectorSpec spec();

    /**
     * Returns the method control is handed to.
     *
     * <p>The same value as {@code spec().handler()}. Identified by owner, name and erased descriptor
     * and nothing else: the class declaring it is read as bytes and never loaded, so a handler is
     * never matched by shape and an overload is a different handler.
     *
     * @return the handler reference
     */
    @Contract(pure = true)
    @NotNull
    HandlerRef handler();

    /**
     * Returns the class an emitted call to the handler must name.
     *
     * <p>{@link HandlerRef#owner()} for every entry except one that dissolves into its target and
     * whose handler is one of the members moved there; for that one it is {@link #target()}, because
     * the handler has become a method of the target. An injector emitting the call uses this rather
     * than {@link HandlerRef#owner()}, since naming the weave class would emit a call to a class the
     * woven artefact may not contain.
     *
     * @return the class to name in the call
     */
    @Contract(pure = true)
    @NotNull
    ClassDesc handlerOwner();

    /**
     * Returns the binary name of the weave class that declared this.
     *
     * <p>Dotted, as {@code com.acme.AuditWeave}. The first tie-breaker of the entry order, and the
     * name a diagnostic uses to say which weave a problem belongs to. Never blank.
     *
     * @return the declaring weave class, as a binary name
     */
    @Contract(pure = true)
    @NotNull
    String weaveClassName();

    /**
     * Returns where the declaration came from.
     *
     * <p>The mechanism that produced it and, when there is one, the more specific place that
     * mechanism found it. This is provenance for a diagnostic rather than anything the engine
     * dispatches on.
     *
     * @return the origin
     */
    @Contract(pure = true)
    @NotNull
    Origin origin();

    /**
     * Returns the declaring weave's ordering priority.
     *
     * <p>The {@code priority} element of the {@code @Weave} annotation on
     * {@link #weaveClassName()}, defaulting to {@code 0}. It is a property of the weave class, so
     * every entry produced by one weave carries the same value.
     *
     * <p>Higher sorts first, which for two wraps meeting at one operation means the highest priority
     * is the outermost and the others nest inside it, and for two declarations reaching one position
     * means the highest priority emits first. Equal priorities are not a conflict; the remaining
     * tie-breakers decide between most such entries, but not the tie cases named above, where they
     * compare equal too.
     *
     * @return the priority, higher first
     */
    @Contract(pure = true)
    int priority();
}
