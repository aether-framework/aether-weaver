/**
 * Builds the account of a run that {@code explain(true)} asks for, and hears from weaving what a plan
 * cannot say.
 *
 * <p>A plan states what is to be done; only weaving knows what was found. The difference between the
 * two is what this package exists to close, and closing it forces a shape that is otherwise hard to
 * justify: {@link de.splatgames.aether.weaver.engine.explain.ExplainReport} is filled from two sides
 * at two different times.
 *
 * <p>The plan half is known as soon as planning finishes and is read straight out of
 * {@link de.splatgames.aether.weaver.engine.plan.WeavePlan} at render time — the weaves, their
 * targets, the members each dissolves, the declarations each makes, and the order several of them run
 * in where they meet. The other half arrives one resolution at a time, through
 * {@link de.splatgames.aether.weaver.engine.explain.SiteObserver}, while classes are being woven.
 *
 * <h2>Why the resolutions are pushed rather than fetched</h2>
 *
 * <p>A {@link de.splatgames.aether.weaver.engine.explain.Resolution} carries instruction indices into
 * the target method's body as it stood when the point was resolved, and the pipeline rebuilds that
 * body immediately afterwards. There is no later moment at which the same question could be asked, so
 * an observer either hears it then or never learns it.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.inject.WeavingPipeline} reports once per point of
 * every declaration that reached resolution, including one that matched nothing, and reports again
 * with an empty index list for a declaration whose target method was never found. That is deliberate:
 * a report that stayed silent there would show {@code not woven yet} for a class that was woven,
 * which sends the reader looking for a driver that never offered the class instead of at the selector
 * that named a method the target does not have. Only a namespaced point that fails inside plugin
 * isolation, before resolution, is not reported here at all.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.explain.SiteObserver#NONE} is installed when nothing is
 * listening, so the pipeline never tests for {@code null}, and a run without a report costs it a call
 * that returns.
 *
 * <h2>Keys</h2>
 *
 * <p>A resolution is filed under four parts — the target, the weave, the declaration's identifier and
 * the rendered point — and the report rebuilds that same key while rendering. The two spellings have
 * to stay in step; a key that no longer matches shows every declaration as {@code not woven yet}
 * rather than failing. The point travels as the string
 * {@link de.splatgames.aether.weaver.engine.explain.Resolution#pointOf} produces precisely so that one
 * text serves both as part of the key and as the column a reader sees. Two declarations differing
 * only in something that rendering leaves out share a key and overwrite one another.
 *
 * <h2>What a driver has to supply</h2>
 *
 * <p>The configuration block is not the engine's to write: nothing here can say which layer decided a
 * setting, so the block is absent from a report nobody hands one to rather than printed empty. A
 * driver calls
 * {@link de.splatgames.aether.weaver.engine.explain.ExplainReport#configuration(String, java.util.List)}
 * with a summary line and one entry per setting worth attributing.
 *
 * <h2>Counting, and thread safety</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.explain.ExplainReport#note} counts severities for the
 * footer and keeps nothing else; the diagnostics themselves have already gone to whatever listener
 * reported them, and {@code INFO} and {@code DEBUG} are not counted at all, since a footer that grew
 * with every informational line would stop being a summary of what went wrong.
 *
 * <p>Weaving runs on whatever thread a driver loads classes on, so the resolutions map and the two
 * counters are concurrent and the configuration is volatile. Rendering may happen at any point and
 * describes what is known then; it is not a snapshot of a finished run.
 *
 * <h2>What is not here</h2>
 *
 * <p>Nothing in this package reports a diagnostic, and nothing decides anything about weaving. It
 * observes, counts and renders. A run without {@code explain(true)} builds no report and falls back to
 * {@link de.splatgames.aether.weaver.engine.plan.WeavePlan#explain()}, which lists the plan and knows
 * nothing about what matched.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.explain;
