/**
 * The inlay preview: the code a weave injects, drawn into the file it is injected into, and the target lines an
 * injection reaches, named beside the injection that reaches them.
 *
 * <p>Two highlighting passes, reading the same relation from its two ends.
 * {@link de.splatgames.aether.weaver.idea.preview.WeaveInlayPass} draws a block of handler code above the line of the
 * target the handler applies to, so a woven method shows what runs inside it without the weave being opened.
 * {@link de.splatgames.aether.weaver.idea.preview.InjectionSiteHintPass} answers the opposite question in one line of
 * text at the end of an injection's own first line: which lines of the target that injection lands on. Both rest on
 * {@link de.splatgames.aether.weaver.idea.preview.WeaveBlocks}, which turns a handler and its {@code @At} into offsets
 * in the target.
 *
 * <p>Nothing in the package is reached from another package of the plugin. The platform enters it through the two
 * pass factories and the one action declared in {@code plugin.xml}, and through the two application services declared
 * by their {@code @Service} annotations.
 *
 * <h2>The two passes</h2>
 *
 * <p>{@code plugin.xml} declares
 * {@link de.splatgames.aether.weaver.idea.preview.WeaveInlayPass.Factory} and
 * {@link de.splatgames.aether.weaver.idea.preview.InjectionSiteHintPass.Factory} as a
 * {@code highlightingPassFactory}. Each class is its own registrar and registers itself with the same arguments:
 * {@code this}, {@code null} for both pass-order arguments, {@code false}, and {@code -1}. Neither factory declines a
 * file — {@code createHighlightingPass} always returns a pass — so whether anything is drawn is settled when that
 * pass collects, not when it is created.
 *
 * <p>Both passes are split the way their supertype splits them, and split it the same way. {@code doCollectInformation}
 * works over PSI and leaves its result in a field; {@code doApplyInformationToEditor} turns that field into inlays and
 * recomputes nothing. The second half treats what it holds as possibly stale: a hint whose zero-based line is equal
 * to or past the document's new line count, and a block whose offset is past the document's new length, are
 * skipped rather than clamped. Because lines are zero-based, equalling the count is what an edit that removes the
 * hint's own last line ordinarily produces, not a rare overshoot, so a document that shrank between the halves
 * loses that inlay instead of receiving it somewhere it does not describe.
 *
 * <p>Inlays are reconciled, not rebuilt. Each pass indexes the inlays of the whole document by offset, keeping only
 * those whose renderer is the one that pass itself installs, so nothing another plugin put in the editor is disposed.
 * Each collected item then claims its offset: an indexed inlay there that still matches is left as the same
 * object, an indexed inlay there that no longer matches is disposed and replaced, an offset with no indexed inlay
 * gets a new one without anything being disposed, and every indexed inlay nothing claimed is disposed at the end. What
 * counts as still matching differs by pass — the hint compares its renderer's text, the block compares
 * {@link de.splatgames.aether.weaver.idea.preview.WeaveBlock#sections()} — and neither compares an identity.
 *
 * <p>The index is keyed by offset in both. For blocks that is a correctness requirement rather than a convenience:
 * {@link de.splatgames.aether.weaver.idea.preview.WeaveBlock#id()} is not unique within a file, while
 * {@link de.splatgames.aether.weaver.idea.preview.WeaveBlocks} produces exactly one block per offset.
 *
 * <p>{@link de.splatgames.aether.weaver.idea.preview.WeaveInlayPass#shownIn(com.intellij.openapi.editor.Editor)} reads
 * the blocks an editor is currently drawing back off its inlay model rather than recomputing them; an editor no pass
 * has applied to answers empty.
 *
 * <h2>The block model</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.idea.preview.WeaveBlock} and its three nested types are values: an offset, an
 * identity, and a list of {@link de.splatgames.aether.weaver.idea.preview.WeaveBlock.Section}s, each holding a
 * {@link de.splatgames.aether.weaver.idea.preview.WeaveBlock.Kind}, a header, an explanation and its code as lines of
 * {@link de.splatgames.aether.weaver.idea.preview.WeaveBlock.Fragment}. No PSI, no document and no editor is reachable
 * from any of them, which is what lets a pass keep one across the two halves of its own work and compare it against
 * what the next pass found.
 *
 * <p>A block is per line, not per handler. Every offset a handler lands on is normalised to the start of its line
 * before it is grouped, when the file has a document to normalise against; without one, two matches on one line stay
 * two offsets and become two blocks. Everything that applies anywhere on one line ends up in that line's single block.
 *
 * <p>Two orderings apply, and they are not the same ordering.
 *
 * <ul>
 *   <li><b>Sections stack in a fixed order of points</b>, not in the order the code they describe runs:
 *       {@code HEAD}, {@code FIELD}, {@code NEW}, {@code INVOKE}, {@code INVOKE_AFTER}, {@code CONSTANT},
 *       {@code THROW}, {@code RETURN}, {@code TAIL}, and within one point
 *       {@link de.splatgames.aether.weaver.idea.preview.WeaveBlock.Kind#INJECT} before
 *       {@link de.splatgames.aether.weaver.idea.preview.WeaveBlock.Kind#REDIRECT}. A point outside that list becomes
 *       no section at all.
 *   <li><b>Handlers inside one section are in execution order</b>, sorted by
 *       {@link de.splatgames.aether.weaver.idea.psi.HandlerOrder#EXECUTION_ORDER}: highest {@code @Weave(priority)}
 *       first, then the weave's qualified class name, the handler's name and its parameter types. The priority is
 *       read only from an integer literal, so {@code @Weave(priority = Priorities.HIGH)} sorts as {@code 0} rather
 *       than by the constant's value, and the comparator is not total — two overloads whose parameter types differ
 *       only by package, or any two handlers whose weave class has no qualified name, compare equal, and then keep
 *       whatever order {@code ReferencesSearch} happened to hand them in, which can change with what was edited
 *       last. A handler that applies several times on the line is listed once, with the count added to its name in
 *       the header rather than its body drawn again.
 * </ul>
 *
 * <p>Only a {@code @Redirect} produces a {@link de.splatgames.aether.weaver.idea.preview.WeaveBlock.Kind#REDIRECT}
 * section; a {@code @Wrap} is drawn as an injection. The kind chooses the section's colour and the wording of its
 * explanation, and splits one point's handlers into two sections where both kinds apply there.
 *
 * <p>A handler's body is drawn without its braces and with the indentation its non-blank lines have in common removed,
 * so it lines up with the target rather than with the weave. Blank lines around it go and blank lines inside it stay.
 * A body of more than twelve lines keeps its first eleven and ends with a line counting the rest.
 * {@link de.splatgames.aether.weaver.idea.preview.WeaveBlock#height()} reports what the block costs in editor lines:
 * one per section header plus one per rendered code line.
 *
 * <p>The identity is built from the target's rendered signature and the section headers, and not from the offset,
 * which is what lets a folded block stay folded while the text above it is edited. Two blocks in one method that carry
 * the same handlers at the same point therefore share an identity and fold together.
 *
 * <h2>Where a block's position comes from</h2>
 *
 * <p>Chosen per handler, by
 * {@link de.splatgames.aether.weaver.idea.preview.WeaveBlocks}. A declaration that pins an {@code ordinal}, names a
 * {@code shift} or carries a {@code slice} counts instructions rather than source constructs, and a target method may
 * have no PSI body to walk at all; in either case the point is resolved against the compiled class through
 * {@link de.splatgames.aether.weaver.idea.bytecode.TargetOperations}, mapped to class file lines by
 * {@link de.splatgames.aether.weaver.idea.bytecode.CompiledLines} and to lines of the file on screen by
 * {@link de.splatgames.aether.weaver.idea.bytecode.EditorLines}. Every other declaration is matched against the
 * target's own source, point by point.
 *
 * <p>None of the vocabulary is re-implemented here. The handlers that name a target are found over the scope
 * {@link de.splatgames.aether.weaver.idea.index.WeaveTargetIndex} supplies, counting only a
 * {@link de.splatgames.aether.weaver.idea.selector.SelectorReference}. Only two of the six source matchers hand a
 * target to the API's own {@code MemberSelector}: a {@code FIELD} point's target is parsed as a field selector and
 * an {@code INVOKE} or {@code INVOKE_AFTER} point's target is parsed as a method selector. A {@code NEW} point
 * names a class rather than a member, so its target and a {@code THROW} point's target are instead turned from
 * slashes to dots and compared as text against a resolved type's qualified and simple names, and a
 * {@code CONSTANT} point's target is compared as text against a literal rendered by
 * {@link de.splatgames.aether.weaver.idea.psi.CaretAnchors}. On the compiled path the kind of member a point's
 * target names is asked of {@link de.splatgames.aether.weaver.engine.parse.PointTargets}, which answers
 * {@code null} for exactly the points whose target stays text on the source path, and that {@code null} is what
 * keeps the target as raw text on the compiled path too. Owners are matched by
 * {@link de.splatgames.aether.weaver.idea.psi.SelectorTargets}. A text that does not parse is no answer rather
 * than an error.
 *
 * <h2>Drawing</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.idea.preview.WeaveBlockRenderer} draws each section as a band the width of the
 * editor: a fill that is mostly the editor's own background blended towards the scheme's diff colour for the section's
 * kind, an undiluted bar of that colour down the left edge, the header in italics, and the code drawn fragment by
 * fragment in the colours the Java highlighter chose. A fragment carrying no colour key is drawn in the scheme's
 * default foreground. The renderer also owns the gutter chevron that folds the block, and the hover text on that
 * chevron, which is where a section's explanation is shown; the header carries only the handler names and the point's
 * tag.
 *
 * <p>{@code InjectionSiteHintRenderer} draws a hint: one string in the scheme's italic editor font, after a fixed gap,
 * in the scheme's inline parameter hint foreground or its default foreground when the scheme names no such colour. It
 * is also the hint's identity, since the pass compares its text.
 *
 * <p>{@link de.splatgames.aether.weaver.idea.preview.InjectionSiteHints} produces the text, and offers a hint for an
 * {@code @Inject}, a {@code @Redirect} or a {@code @Wrap} and for nothing else. A single site names one
 * line; several are counted and then listed, and past six the remainder is counted instead of listed. The lines it
 * names are one-based lines of the target's own file, which need not be the file the hint is drawn in, while
 * {@link de.splatgames.aether.weaver.idea.preview.InjectionSiteHints.Hint#line()} is a zero-based line of the file
 * that was scanned. The two numbering schemes belong to different documents and are never compared.
 *
 * <h2>Switching the feature off, and folding one block</h2>
 *
 * <p>Two separate pieces of state, at two different granularities.
 *
 * <ul>
 *   <li>{@link de.splatgames.aether.weaver.idea.preview.WeaveInlaySettings} is one flag for the whole feature, held as
 *       the state named {@code AetherWeaverInlayPreview} in {@code aether-weaver.xml} and clear on a fresh instance.
 *       {@link de.splatgames.aether.weaver.idea.preview.ToggleInjectedCodeAction} is its only writer: the
 *       "Show Injected Code" check item, ticked when the flag is clear, which after writing the flag restarts the
 *       daemon of every open, undisposed project rather than of the one its event came from. With the flag set,
 *       {@link de.splatgames.aether.weaver.idea.preview.WeaveInlayPass} collects nothing at all, and reconciliation
 *       then disposes every block inlay this package put in the editor.
 *   <li>{@link de.splatgames.aether.weaver.idea.preview.WeaveCollapsedBlocks} is a set of block identities, held in
 *       memory and declaring no state to persist. It is read by
 *       {@link de.splatgames.aether.weaver.idea.preview.WeaveBlockRenderer} alone; the pass never consults it, so a
 *       folded block is still collected and still drawn — as a strip carrying the first section's bar and tint, tall
 *       enough for its chevron, and nothing else. Clicking that chevron toggles the identity and updates that one
 *       inlay.
 * </ul>
 *
 * <p>Either source folds a block, so the feature-wide flag cannot be overridden from the per-block set. Both are read
 * afresh whenever a block's height is measured, painted or asked for its gutter control; measuring a block's width
 * reads neither.
 *
 * <p>Neither piece of state is consulted on the hint side: switching injected code off leaves the end-of-line hints
 * where they are.
 *
 * <h2>What this package does not do</h2>
 *
 * <ul>
 *   <li><b>It reports nothing.</b> No type here raises a diagnostic, and most failures come back as an empty list: a
 *       selector that resolves to nothing or to more than one method, a point this release cannot parse, a
 *       half-typed annotation, a target that has not been compiled where the class file is needed. A file with no
 *       document is not among them — {@link de.splatgames.aether.weaver.idea.preview.WeaveBlocks} still produces
 *       blocks without one, merely without normalising their offsets to the start of a line first. Judging what the
 *       user wrote belongs to the inspections. A block drawn on a guess would be a claim about where code runs.
 *   <li><b>It changes no file.</b> The only state it owns is its own inlays, the one flag and the set of folded
 *       identities.
 *   <li><b>It does not weave.</b> Handler bodies are drawn as their authors wrote them, coloured by the Java
 *       highlighter and cut to length; the block above a line is a description of what applies there, not a rendering
 *       of the bytecode that will be produced.
 * </ul>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.idea.preview;
