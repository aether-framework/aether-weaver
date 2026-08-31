/**
 * Writes the before and after of a woven class, together with a diff of the two that a person can
 * read.
 *
 * <p>Everything else the engine says about a run describes declarations: the plan lists what was to
 * be done, the explain report lists what matched. This package describes bytes, and it is the only
 * place that does. It answers the question a plan cannot — what actually changed in this class —
 * and it answers it by writing the two class files out and disassembling both.
 *
 * <p>Nothing here is on the weaving path. {@link de.splatgames.aether.weaver.engine.Weaver} does not
 * name this package; a {@link de.splatgames.aether.weaver.engine.dump.ClassDump} is constructed by a
 * driver — the agent's transformer, the weaving class loader and the Maven goals each build one when
 * a dump directory was configured — and is handed the original and woven bytes after the weaver has
 * returned them.
 *
 * <h2>What one dump produces</h2>
 *
 * <p>Three files per class, named after the class and placed under its package, so that a dump
 * directory has the shape of a class hierarchy and two classes of the same simple name do not
 * collide: {@code .original.class}, {@code .woven.class} and {@code .diff.txt}. The two class files
 * are written first and the diff last, so a failure part-way through leaves what was already
 * written.
 *
 * <h2>Why the diff is built here rather than taken from a library</h2>
 *
 * <p>Weaving shifts every bytecode offset and renumbers the constant pool, so a plain line-by-line
 * comparison of {@code javap} output reports an entire method as changed for one inserted
 * instruction. {@link de.splatgames.aether.weaver.engine.dump.Disassembly#key(String)} removes those
 * two things and nothing else, and {@link de.splatgames.aether.weaver.engine.dump.TextDiff} compares
 * by that key while printing the lines as they were written — so the output shows real offsets and
 * real indices, and only the instructions that genuinely differ.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.dump.Disassembly} reaches {@code javap} through
 * {@link java.util.spi.ToolProvider}, so there is no JDK to locate and no process to start. The tool
 * lives in the {@code jdk.javap} module, which a trimmed runtime image may leave out; a run without
 * it still writes both class files and puts the command to run elsewhere into the diff file instead
 * of the hunks.
 *
 * <h2>A dump never costs a class</h2>
 *
 * <p>A dump runs inside class loading. Every failure — an unwritable directory, and equally a class
 * name from the class file that would resolve outside the dump directory — is reported as
 * {@code AW4090} and none is thrown, so the caller still receives the bytes it was about to define.
 * A class file {@code javap} refuses is not reported at all: it is written into the diff file as the
 * finding, since a woven class the disassembler will not read is itself worth knowing about.
 *
 * <h2>What is not here</h2>
 *
 * <p>Nothing decides whether to dump; that is a driver's configuration. Nothing compares structure —
 * the comparison is over disassembled text, so two class files that differ only in constant-pool
 * ordering compare equal and say so. And nothing parses a class file: the reading is
 * {@code javap}'s, which is what keeps this package independent of how the engine models a class.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.dump.Disassembly} and
 * {@link de.splatgames.aether.weaver.engine.dump.TextDiff} carry no state and no dependency on the
 * rest of the engine, and the test kit's golden-file comparison uses them directly.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.dump;
