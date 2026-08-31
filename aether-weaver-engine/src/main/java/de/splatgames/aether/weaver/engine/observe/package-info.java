/**
 * What a run says about itself while it happens: counters, and Flight Recorder events.
 *
 * <p>Diagnostics answer what went wrong and the explain report answers what was planned. This package
 * answers neither. It records how much work a run did — how many classes were offered, how many came
 * back changed, how many declarations were applied to them, how many were refused by the verifier and
 * how long it all took — and makes that available both as a value a driver can print and as an event
 * a profiler can record.
 *
 * <h2>Counters</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.observe.Statistics} is created once per
 * {@link de.splatgames.aether.weaver.engine.Weaver}, with the number of targets the plan names fixed
 * at construction; every other count is a {@link java.util.concurrent.atomic.LongAdder}, because in
 * an agent the weaver is entered on whichever thread happens to be loading a class. The planned count
 * is what the rest is read against: a plan naming more targets than were ever offered is a weave that
 * did not apply, and nothing else in a run says so.
 *
 * <p>Two of the counts are narrower than they look, and both are decided by the weaver rather than
 * here. A class is counted as seen before the plan is consulted, so every class an agent passes
 * through untouched is included. A class is counted as woven only once weaving has committed to
 * handing the new bytes back, which is after verification — so a class whose woven form the verifier
 * refused is counted as a failure and not also as a weave, and a class the extension pass alone
 * changed is counted as seen and as nothing else. Time is recorded only for classes the plan names.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.observe.Statistics#snapshot()} reads the adders one
 * after another without a lock, so a snapshot taken during weaving may hold counts from slightly
 * different moments. It answers a
 * {@link de.splatgames.aether.weaver.api.spi.StatisticsView}, which is the form the rest of the world
 * sees.
 *
 * <h2>Flight Recorder, on a runtime that may not have it</h2>
 *
 * <p>A JVM without the {@code jdk.jfr} module is a supported JVM, and the shape of this package is
 * decided by that. {@link de.splatgames.aether.weaver.engine.observe.WeaveEvents} is an interface
 * that mentions nothing from {@code jdk.jfr}; the package-private implementation that emits events is
 * named in code nowhere outside {@link de.splatgames.aether.weaver.engine.observe.WeaveEvents#discover()},
 * which names it as a string and reaches it reflectively, and every way that can fail ends in
 * {@link de.splatgames.aether.weaver.engine.observe.WeaveEvents#NONE}. No field, cast or {@code new}
 * anywhere in the rest of the engine names the JFR implementation, so loading and linking every other
 * class stays possible without the module present.
 *
 * <p>The fallback is a null object rather than a {@code null}, so the weaving path costs a call that
 * returns instead of a null check per class. Absence is reported nowhere: a runtime without JFR is a
 * normal runtime, and a framework that warned about it at every start would be teaching people to
 * skip its warnings.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.observe.WeaveEvents#enabled()} is asked before the
 * event's values are gathered, so a class nobody is recording is not timed a second time to fill in a
 * field no consumer will read. The emitting implementation then asks a second time, through the
 * event's own {@code shouldCommit()} once every field has been filled in, and commits only if that
 * agrees.
 *
 * <h2>What is not here</h2>
 *
 * <p>No logging and no listener. A diagnostic goes to
 * {@link de.splatgames.aether.weaver.api.spi.DiagnosticListener} and never through this package, and
 * nothing here reports a diagnostic of its own.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.observe;
