/**
 * Class-file surgery: the operations the weaving stages perform on bytes, factored out of the stages
 * that perform them.
 *
 * <p>Nothing here knows what a weave is. Four of these answer a question the class-file API poses to
 * anyone moving code from one class into another — what becomes of a type reference, of a label, of a
 * slot number, and of the stack maps the result needs. A fifth answers a different question, what a
 * body's debug information already says about a slot, and the sixth is the attribute a woven class
 * carries. Each is separated out because getting it wrong
 * produces a class file that verifies and then behaves incorrectly, which is the failure this package
 * exists to take away from the stages above it.
 *
 * <h2>Why the package is called internal</h2>
 *
 * <p>The name is load-bearing and is enforced. {@code ProjectStructureTest} asserts that no module but
 * the engine imports anything from a package whose path contains {@code .internal.}, and additionally
 * that no engine type outside such a package names one of these in a {@code public} or
 * {@code protected} signature — which would make a type carrying no compatibility promise part of the
 * published API by accident. Within the engine these are ordinary types and are meant to be used:
 * reading the rule as "nothing may reference them" would make the package dead code.
 *
 * <h2>The stamp's on-disk form</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.internal.transform.WeaveAttribute} is the
 * {@code AetherWeave} attribute: its layout, its reader, its writer, and the
 * {@link java.lang.classfile.ClassFile} context that has the mapper installed. What goes into it is
 * decided in {@link de.splatgames.aether.weaver.engine.stamp}; how it is spelled is decided here.
 *
 * <p>It is an attribute rather than an annotation because an annotation would join the class's
 * observable annotation set and change what every framework that enumerates annotations sees. What
 * makes it survive an unrelated tool is not its declared stability, which such a tool never consults,
 * but the class-file API's default of passing unknown attributes through a transform unread — and that
 * default is enough only while the constant-pool indices in its payload still point into the pool they
 * were written against, which fails for raw bytes carried across a rebuilt pool. What actually saves
 * this attribute is that a transform never sees it as raw bytes to begin with: once this class's own
 * mapper is installed, the attribute arrives decoded, and writing a decoded attribute re-interns its
 * strings into whichever pool is active at that moment.
 * {@link de.splatgames.aether.weaver.engine.internal.transform.ClassRemapper} names no case for it and
 * forwards it through its {@code default} branch like any other element it does not recognise; the
 * mapper does the decoding and the rewriting, not the remapper.
 *
 * <h2>Renaming a type</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.internal.transform.ClassRemapper} rewrites every type
 * reference in a class. Every one is the difficult part: a class file names a type in a dozen places
 * that have nothing to do with each other, and missing one leaves the old name in the constant pool of
 * a class that no longer has anything of that name to find. Two properties of its entry point are not
 * properties of the transform on its own — a class the mapping does not touch is returned byte for
 * byte rather than rebuilt, and the rewrite runs with a fresh constant pool, without which the
 * replaced names remain interned in it. A caller that drives the transform itself has to set that
 * option, and
 * {@link de.splatgames.aether.weaver.engine.internal.transform.ClassRemapper#requiredPoolOption()} is
 * where it says so. A mapping that covers the class's own name renames the class, which is what
 * folding a weave into its target needs.
 *
 * <h2>Moving a body</h2>
 *
 * <p>Three of these exist because a body is not portable. A
 * {@link java.lang.classfile.Label} belongs to the code that created it, so
 * {@link de.splatgames.aether.weaver.engine.internal.transform.CodeRelabeler} rebuilds every
 * label-bearing element around a label of the receiving builder — one mapping per body, and one body
 * per relabeler. A slot number means something else in another frame, so
 * {@link de.splatgames.aether.weaver.engine.internal.transform.LocalsShifter} splits the frame at a
 * fixed prefix — the receiver and the parameters, counted in slots rather than in parameters, so that
 * the second half of a wide parameter does not move away from its first — and moves everything above
 * it by a constant. Both cover the debug pseudo-elements as well as the instructions: unshifted or
 * unrelabelled debug information produces code that runs correctly and is impossible to debug.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.internal.transform.LocalTable} reads the other
 * direction: it indexes a body's {@code LocalVariable} entries by element position rather than by the
 * pair of labels the class file states them as, so that a caller asking what occupies a slot at a
 * position compares two integers. Debug information is optional, and the table says so rather than
 * reconstructing anything —
 * {@link de.splatgames.aether.weaver.engine.internal.transform.LocalTable#isAvailable()} is
 * {@code false} for a class compiled without it and every lookup answers empty, because a wrong slot
 * named confidently is worse than no slot named.
 *
 * <h2>Stack maps</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.internal.transform.FrameSupport} builds a
 * {@link java.lang.classfile.ClassFile} context that generates stack maps and answers the hierarchy
 * questions that generating them raises by parsing class <em>resources</em>, falling back to the
 * default resolver only for what no resource yields. The default resolver reflects over the system
 * class loader and, by its own specification, loads system classes that are not yet loaded, which is
 * not something an instrumentation path may do. Its build-time and load-time entry points return the
 * same configuration deliberately: a class woven during the build and the same class woven as it loads
 * must not end up with different frames because their hierarchies were resolved by different
 * strategies.
 *
 * <h2>Who uses what</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.internal.transform.WeaveAttribute} is read and written
 * by {@link de.splatgames.aether.weaver.engine.stamp} and by
 * {@link de.splatgames.aether.weaver.engine.Weaver};
 * {@link de.splatgames.aether.weaver.engine.internal.transform.ClassRemapper} is used by
 * {@link de.splatgames.aether.weaver.engine.merge}, which composes it after its own rebinding
 * transform; {@link de.splatgames.aether.weaver.engine.internal.transform.LocalTable} is used by
 * {@link de.splatgames.aether.weaver.engine.inject} to resolve captured locals. The other three are
 * named by no other class in the engine's main sources and are covered by tests of their own —
 * {@code BodyMergeTest} for the relabeler and the shifter, {@code FrameSupportTest} for the frame
 * context.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.internal.transform;
