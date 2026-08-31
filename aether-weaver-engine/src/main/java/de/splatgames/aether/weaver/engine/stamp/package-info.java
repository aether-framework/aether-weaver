/**
 * Records what was done to a class, on the class itself, and reads it back.
 *
 * <p>A woven class file outlives the run that produced it. It is written by a build, loaded by a
 * different JVM, and may be offered to a second weaver that has no plan in front of it and no way to
 * ask. The stamp is what that reader has: it names the weaver version, the fingerprint of the plan
 * that was applied, a flag word, and one entry per declaration — the weave class, the injector kind,
 * the handler as name and descriptor, and the target selector as its author wrote it. All of it is
 * text, because a reader has nothing to resolve those names against.
 *
 * <h2>Two carriers, one record</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.stamp.WeaveRecord} is built once per class and both
 * carriers are written from it, so the version, the fingerprint and the entries cannot disagree
 * between them; each writer only chooses what to leave out.
 *
 * <ul>
 *   <li>{@link de.splatgames.aether.weaver.engine.stamp.WeaveAttributeWriter} writes the
 *       {@code AetherWeave} attribute, whatever detail level was asked for. It is the machine-readable
 *       half, it is invisible to reflection, and it caps nothing.
 *   <li>{@link de.splatgames.aether.weaver.engine.stamp.WovenAnnotationWriter} writes the
 *       {@code @Woven} annotation, and how much it writes is the caller's choice:
 *       {@code Woven.Detail.NONE} produces no annotation at all rather than an empty one,
 *       {@code SUMMARY} writes everything but the per-declaration listing, and {@code FULL} adds that
 *       listing capped at {@link de.splatgames.aether.weaver.engine.stamp.WeaveRecord#MAX_ANNOTATION_ENTRIES}.
 *       It is also the only carrier of the plugin coordinates and the plugin metadata; the attribute
 *       holds neither, so under {@code NONE} nothing on the class carries them at all.
 * </ul>
 *
 * <p>The truncation flag exists because of that cap and belongs to the carrier rather than to the
 * record, which is why
 * {@link de.splatgames.aether.weaver.engine.stamp.WeaveRecord#flags(boolean)} takes it as an argument
 * and derives the word on each call. The attribute is always written with truncation {@code false}.
 *
 * <h2>Determinism</h2>
 *
 * <p>Everything without a natural order is put into one.
 * {@link de.splatgames.aether.weaver.engine.stamp.WeaveRecord#of} sorts the weave names and the plugin
 * coordinates, the metadata is held in a {@link java.util.TreeMap} and iterates by key, and the
 * entries keep the order the plan held them in — so the same inputs stamp the same bytes.
 *
 * <h2>Reading it back</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.stamp.Provenance} answers whether a class carries a
 * stamp, from which plan, and what it says. This is what makes weaving idempotent:
 * {@link de.splatgames.aether.weaver.engine.Weaver} compares the stamped fingerprint against its own
 * plan's before weaving a class, so build-time weaving followed by a load-time driver does not apply
 * every injection twice.
 *
 * <p>One precondition governs the whole package and is easy to get wrong. The attribute is readable
 * only through a {@link java.lang.classfile.ClassFile} that has the mapper installed, which is what
 * {@code WeaveAttribute.classFileWithMapper} builds. A model parsed by a plain {@code ClassFile.of()}
 * still holds the attribute in the class file but exposes it as an unknown one, so every lookup
 * answers empty and every class looks unwoven — including one this weaver stamped moments earlier.
 * The {@code byte[]} overloads of
 * {@link de.splatgames.aether.weaver.engine.stamp.Provenance} parse with the mapper themselves and
 * have no such precondition, which is why the idempotence gate uses them.
 *
 * <p>What comes back out of
 * {@link de.splatgames.aether.weaver.engine.stamp.Provenance#recordOf(byte[])} is not the record that
 * went in: the attribute carries no plugin coordinates and no metadata, so both come back empty, and
 * the weave names are recovered as the distinct owners of the entries rather than read from a list of
 * their own.
 *
 * <h2>What is not here</h2>
 *
 * <p>The attribute's byte layout, its reader and its writer are
 * {@code de.splatgames.aether.weaver.engine.internal.transform.WeaveAttribute}'s; this package decides
 * what goes into it. Nothing here reports a diagnostic, and nothing here decides whether a class may
 * be woven — a stamp is evidence, and the gate that acts on it belongs to
 * {@link de.splatgames.aether.weaver.engine.Weaver}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.stamp;
