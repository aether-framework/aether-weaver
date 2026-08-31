/**
 * Turns the parsed weaves into the ordered, indexed plan a weaver runs from, and does it once.
 *
 * <p>Weaving asks one question of every class the JVM loads — does this class need anything done to
 * it — and answers "no" almost every time. Everything in this package exists to make that answer cost
 * two map lookups. {@link de.splatgames.aether.weaver.engine.plan.WeavePlanner#plan} runs before the
 * first class is offered, flattens the declarations, orders them, checks them against one another and
 * digests the result; {@link de.splatgames.aether.weaver.engine.plan.WeavePlan} is then only read.
 *
 * <h2>Flattening</h2>
 *
 * <p>A {@link de.splatgames.aether.weaver.engine.plan.PlanEntry} is one injector declaration paired
 * with one class it applies to, so a weave naming three targets and declaring two injectors becomes
 * six entries, each of which can be acted on without looking at the weave again. A weave that
 * dissolves is additionally recorded against each of its targets in a second index, which is what
 * keeps a weave that declares members and no injection at all from vanishing out of the plan's target
 * list — {@link de.splatgames.aether.weaver.engine.plan.WeavePlan#targets()} is the union of the two,
 * and both {@link de.splatgames.aether.weaver.engine.plan.WeavePlan#entriesFor(String)} and
 * {@link de.splatgames.aether.weaver.engine.plan.WeavePlan#structuralFor(String)} have to be consulted
 * before a class can be dismissed.
 *
 * <h2>Order</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.plan.OrderKey} decides which of two declarations
 * meeting at one place runs first: priority descending, then weave class name, then handler name,
 * then handler descriptor. The key names a handler and not an entry — nothing in it mentions the
 * target, the injector kind or the injection point — so two entries of one weave for one handler
 * compare equal, and the order is total only because the planner sorts with a stable sort over
 * entries built in parse order.
 *
 * <p>The sort happens before anything else looks at the entries, so conflict detection, the
 * fingerprint, the report and the weaving all see one sequence, and the per-target lists keep it.
 *
 * <h2>Conflicts</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.plan.ConflictDetector} compares declarations against
 * one another and reads no class file at all, which is both why its checks are worth running here —
 * they answer for the whole run at once instead of once per loading class — and where they stop. Its
 * call-site key is built from the text the author wrote, so two declarations that reach one
 * instruction through different spellings are two sites to it and neither is reported.
 *
 * <p>Four codes, listed by
 * {@link de.splatgames.aether.weaver.engine.plan.ConflictDetector#reportableCodes()}:
 *
 * <ul>
 *   <li>{@code AW1087} for a weave whose target is another weave of the same run, itself included.
 *   <li>{@code AW1060} where two declarations claim one call site and at least one is a redirect —
 *       any number of wraps at one site nest, so a site without a redirect is skipped however many
 *       claimants it has.
 *   <li>{@code AW1080} for two members that would land in one target under the same name: a merged
 *       member matched by name and type, or — only for weaves that dissolve — a handler matched by
 *       name and descriptor. The merged-member check does not filter on whether a weave dissolves, so
 *       two static weaves each declaring a plain field of one name and type at one target are
 *       reported too, though neither field actually merges into anything. A merged-member collision
 *       is excused only when every claimant is {@code @Unique}.
 *   <li>{@code AW1034} where one weave shadows a member another weave merges without a strictly
 *       higher priority, and so without running first.
 * </ul>
 *
 * <p>None of them stops the plan. Every pass runs, every finding is reported, and a plan is returned
 * either way, so one run shows everything that is wrong rather than one problem per rebuild; whoever
 * owns the listener decides what an error means. Four of the five passes iterate the weaves sorted by
 * binary name and collect into a {@link java.util.TreeMap}; {@code duplicateRedirects} iterates the
 * already-sorted call-site entries instead. Either way the sequence of diagnostics does not depend on
 * the order the weaves were discovered in.
 *
 * <h2>The fingerprint</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.plan.PlanFingerprint} is a SHA-256 over everything that
 * decides what the weaving will do, and it is not a cache key. It is stamped into every woven class
 * and compared against that stamp before the class is woven again, so two plans that agree here treat
 * a stamped class as already done, and anything that changes the outcome has to reach the digest
 * while anything that does not must stay out of it. That is why
 * {@link de.splatgames.aether.weaver.engine.model.WeaveClass#origin()} is deliberately absent from
 * {@link de.splatgames.aether.weaver.engine.plan.PlanEntry#canonical()}: the same weave found in two
 * directories is the same modification, and a path in the digest would make two machines disagree
 * over an identical build. The plugins reach it too, through their namespaces and versions, the
 * identifiers they registered and their metadata.
 *
 * <p>One input's order is not imposed here: the dissolving weaves are digested in the order the caller
 * supplied them, so a build with two or more of them can digest differently if they were discovered
 * in a different order.
 *
 * <h2>What is not here</h2>
 *
 * <p>Nothing in this package resolves anything against a target class. No selector is matched, no
 * injector kind is looked up, no injection point is asked where it lands — all of that happens once a
 * target class is in hand, in {@link de.splatgames.aether.weaver.engine.inject}. The plugin registry
 * is read at exactly one place, when the fingerprint is built, and is consulted to resolve nothing.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.plan;
