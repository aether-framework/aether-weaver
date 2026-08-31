package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.engine.text.ConsoleText;
import de.splatgames.aether.weaver.engine.stamp.Provenance;
import de.splatgames.aether.weaver.engine.stamp.WeaveRecord;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lists what was woven into a compiled artefact, from the artefact alone.
 *
 * <p>The {@code audit} goal names no lifecycle phase and declares {@code requiresProject = false},
 * so it is invoked directly rather than bound, and it will run outside a project. It reads the
 * record a woven class carries in an attribute of its own class file: a class carrying none does
 * not appear in the report, and an artefact holding none at all is reported in one line rather than
 * as an empty report. Nothing is written, nothing is re-woven, and no diagnostic is raised.
 *
 * <h2>The report</h2>
 *
 * <p>One line per woven class, ordered by the name the report shows for it, each followed by one
 * indented line per modification that class records, then a blank line and a summary. A modification line holds the
 * declaring weave class padded to {@value #WEAVE_COLUMN} columns, the injector kind upper-cased and
 * padded to {@value #KIND_COLUMN}, the handler with its descriptor, and the target selector as the
 * weave author wrote it.
 *
 * <p>A modification line opens with a leftwards arrow and separates the handler from the target
 * with a rightwards arrow. {@link #execute()} degrades every line to what the charset of
 * {@link System#out} can encode before printing it, which turns those into {@code <-} and
 * {@code ->} on a console that cannot write them; {@code report} itself returns them unchanged. The
 * example is the degraded form.
 *
 * <pre>{@code
 * fixture/Target.class
 *   <- fixture.Greeting                INJECT   onGreet()V  ->  greet()
 *
 * 1 class, 1 modification, fingerprint <plan fingerprint>, no policy overrides
 * }</pre>
 *
 * <p>The summary counts classes and modifications, joins the distinct plan fingerprints of the
 * audited classes with {@code +}, and closes with {@code no policy overrides} or, when any audited
 * class records that one was used, with {@code A POLICY OVERRIDE WAS USED}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Mojo(name = "audit", requiresProject = false, threadSafe = true)
public class AuditMojo extends AbstractMojo {

    /** The width of the weave-class column of a modification line. */
    private static final int WEAVE_COLUMN = 32;

    /** The width of the injector-kind column of a modification line. */
    private static final int KIND_COLUMN = 9;

    /**
     * The jar or the directory of classes to audit.
     *
     * <p>Set from the {@code aether.weaver.artifact} property, and otherwise from the current
     * project's output directory. When neither yields anything the field stays {@code null} and the
     * goal fails, naming that property in its message.
     */
    @Parameter(property = "aether.weaver.artifact",
               defaultValue = "${project.build.outputDirectory}")
    private File artifact;

    /**
     * Creates a mojo whose parameters are not yet set.
     *
     * <p>Maven injects the {@code @Parameter} fields after construction.
     */
    public AuditMojo() {
        // Maven injects the @Parameter fields after construction.
    }

    /**
     * Prints the report for the named artefact at info level.
     *
     * <p>Every line is first degraded to what the charset of {@link System#out} can encode, so a
     * console that cannot write the arrows gets ASCII rather than a row of question marks.
     *
     * @throws MojoExecutionException if no artefact was named, if the named one does not exist, or
     *                                if it cannot be read
     * @throws UncheckedIOException   if a class file inside a named directory cannot be read, which
     *                                is not converted into a {@link MojoExecutionException}
     */
    @Override
    public void execute() throws MojoExecutionException {
        if (this.artifact == null || !this.artifact.exists()) {
            throw new MojoExecutionException("no artefact to audit at " + this.artifact
                    + "; name one with -Daether.weaver.artifact=<jar or directory>");
        }
        try {
            for (final String line : report(this.artifact.toPath())) {
                getLog().info(ConsoleText.forStream(line, System.out));
            }
        } catch (final IOException unreadable) {
            throw new MojoExecutionException(
                    "could not read " + this.artifact + ": " + unreadable.getMessage(),
                    unreadable);
        }
    }

    /**
     * Builds the audit report for one artefact.
     *
     * @param artefact the jar or directory of classes to read; must not be {@code null}
     * @return the report as separate lines, or a single line saying that nothing in the artefact
     *         was woven
     * @throws NullPointerException if {@code artefact} is {@code null}
     * @throws IOException          if the directory cannot be walked or the archive cannot be
     *                              opened
     * @throws UncheckedIOException if a class file inside a directory cannot be read
     */
    @NotNull
    static List<String> report(@NotNull final Path artefact) throws IOException {
        Objects.requireNonNull(artefact, "artefact");
        final List<Audited> audited = read(artefact);

        if (audited.isEmpty()) {
            return List.of("no woven classes in " + artefact.getFileName());
        }

        final List<String> lines = new ArrayList<>();
        int modifications = 0;
        final Set<String> fingerprints = new LinkedHashSet<>();
        boolean overridden = false;

        for (final Audited entry : audited) {
            lines.add(entry.name());
            fingerprints.add(entry.record().fingerprint());
            overridden |= entry.record().policyOverride();
            for (final WeaveRecord.Entry modification : entry.record().entries()) {
                lines.add("  ← " + pad(modification.weave(), WEAVE_COLUMN)
                        + pad(modification.kind().toUpperCase(java.util.Locale.ROOT), KIND_COLUMN)
                        + modification.handler() + "  →  " + modification.target());
                modifications++;
            }
        }

        lines.add("");
        lines.add(audited.size() + " class" + (audited.size() == 1 ? "" : "es") + ", "
                + modifications + " modification" + (modifications == 1 ? "" : "s")
                + ", fingerprint " + String.join(" + ", fingerprints)
                + (overridden ? ", A POLICY OVERRIDE WAS USED" : ", no policy overrides"));
        return lines;
    }

    /**
     * Reads the woven classes out of a directory or an archive.
     *
     * <p>Anything that is not a directory is opened as a zip archive, so a jar, a war and a plain
     * zip are all read the same way.
     *
     * @param artefact the directory or archive to read
     * @return the woven classes it holds, sorted by name; empty when none of them carries the
     *         attribute
     * @throws IOException if the directory cannot be walked or the archive cannot be opened
     */
    @NotNull
    private static List<Audited> read(@NotNull final Path artefact) throws IOException {
        final List<Audited> audited = Files.isDirectory(artefact)
                ? fromDirectory(artefact)
                : fromJar(artefact);
        // Sorted by name, always: an audit is compared against the last one, and an ordering that
        // followed the archive's or the file system's would make every comparison noisy.
        audited.sort(Comparator.comparing(Audited::name));
        return audited;
    }

    /**
     * Collects the woven classes under a directory.
     *
     * <p>A name is the path relative to the directory with the platform separator replaced by
     * {@code /}, so that it reads exactly like the entry name the same class would have in a jar
     * and the two forms of the report agree.
     *
     * @param directory the directory to walk
     * @return the woven classes it holds, in walk order
     * @throws IOException          if the directory cannot be walked
     * @throws UncheckedIOException if one of the class files cannot be read
     */
    @NotNull
    private static List<Audited> fromDirectory(@NotNull final Path directory) throws IOException {
        final List<Audited> audited = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .forEach(path -> {
                        final byte[] bytes;
                        try {
                            bytes = Files.readAllBytes(path);
                        } catch (final IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                        Provenance.recordOf(bytes).ifPresent(record -> audited.add(
                                new Audited(directory.relativize(path).toString()
                                        .replace(File.separatorChar, '/'), record)));
                    });
        }
        return audited;
    }

    /**
     * Collects the woven classes in a zip archive.
     *
     * @param jar the archive to open
     * @return the woven classes it holds, in the archive's own entry order
     * @throws IOException if the archive cannot be opened or one of its entries cannot be read
     */
    @NotNull
    private static List<Audited> fromJar(@NotNull final Path jar) throws IOException {
        final List<Audited> audited = new ArrayList<>();
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            final Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                final byte[] bytes;
                try (InputStream stream = archive.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                Provenance.recordOf(bytes)
                        .ifPresent(record -> audited.add(new Audited(entry.getName(), record)));
            }
        }
        return audited;
    }

    /**
     * Pads a column of a modification line.
     *
     * @param value the text to place in the column
     * @param width the column's width
     * @return the value padded with spaces to {@code width}, or the value followed by a single
     *         space when it already fills the column, so that two columns never run together
     */
    @NotNull
    private static String pad(@NotNull final String value, final int width) {
        return value.length() >= width ? value + ' ' : value + " ".repeat(width - value.length());
    }

    /**
     * One woven class of the artefact being audited.
     *
     * @param name   the class file's name within the artefact, with {@code /} as its separator
     * @param record what the class itself says was done to it
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Audited(@NotNull String name, @NotNull WeaveRecord record) {
    }
}
