package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
import de.splatgames.aether.weaver.api.diagnostic.WeaveException;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.api.spi.StatisticsView;
import de.splatgames.aether.weaver.engine.text.ConsoleText;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.dump.ClassDump;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.engine.extension.ExtensionIndex;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugins.annotations.Parameter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * The shared body of the two goals that weave a directory of compiled classes in place.
 *
 * <p>{@link WeaveMojo} and {@link WeaveTestsMojo} differ in which directory they rewrite, which
 * classpath they read declarations from, and whether they go on to weave dependency jars. The run
 * itself is {@link #weaveDirectory()}, which each subclass calls from its {@code execute()}.
 *
 * <h2>What one run does</h2>
 *
 * <ol>
 *   <li>Returns after one line at info level when {@code skip} is set.
 *   <li>Returns after one line at debug level when {@link #directory()} is not a directory. A
 *       module that compiled nothing is not a failure.
 *   <li>Reads {@code META-INF/aether/weaves.json} from every existing entry of
 *       {@link #classpathElements()} and keeps the extension declarations it finds. A weave
 *       declared by an entry that {@link #directEntries()} does not name is reported as
 *       {@code AW3010}; weaves found on the classpath are not otherwise used.
 *   <li>Reads {@code META-INF/aether/weaves.json} from {@link #directory()} and parses each weave
 *       class it names out of that same directory. This file is the only source of the weaves that
 *       are applied. A module without one is the common case and is not an error.
 *   <li>Returns at debug level when there is neither a weave nor an extension to apply.
 *   <li>Weaves every {@code .class} file under {@link #directory()}, in path order, and writes back
 *       only those the weaver returned new bytes for.
 *   <li>Calls {@link #afterWeaving(Weaver, DiagnosticListener)}, which weaves nothing unless a
 *       subclass overrides it, and then tells the weaver's plugins that weaving is over.
 *   <li>Warns when the plan named more target classes than were woven, prints the explain report
 *       when {@code explain} is set, then logs every collected diagnostic and fails the build when
 *       any of them has error severity and {@code failOnError} is left set.
 * </ol>
 *
 * <h2>What is read and what is written</h2>
 *
 * <p>The weaver's class source is {@link ClassSource#ofPath(Path)} over {@link #directory()}, so a
 * class it needs but was never asked to weave is still readable. Files the weaver did not change
 * keep their content and their modification time.
 *
 * <p>An {@link IOException} while reading or writing a class file becomes an
 * {@link UncheckedIOException} and escapes the goal rather than a {@link MojoExecutionException}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public abstract class AbstractWeaveMojo extends AbstractMojo {

    /**
     * Whether the goal returns without looking at anything.
     *
     * <p>Set from the {@code aether.weaver.skip} property and {@code false} when neither that
     * property nor the plugin configuration names it. {@link StubsMojo} reads the same property, so
     * setting it on the command line disables stub generation as well.
     */
    @Parameter(property = "aether.weaver.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Whether a collected diagnostic of error severity fails the build.
     *
     * <p>Set from the {@code aether.weaver.failOnError} property. It defaults to {@code true} both
     * in the annotation and in the field initialiser, so an instance Maven never injected also
     * fails on an error. Cleared, the errors are still logged at error level and the woven classes
     * are still written.
     */
    @Parameter(property = "aether.weaver.failOnError", defaultValue = "true")
    private boolean failOnError = true;

    /**
     * Whether the weaver's explain report is printed, one line at a time, at info level.
     *
     * <p>Set from the {@code aether.weaver.explain} property, {@code false} by default. The report
     * is taken after weaving rather than before, so it names what each injection point matched
     * rather than only what the plan intended.
     */
    @Parameter(property = "aether.weaver.explain", defaultValue = "false")
    private boolean explain;

    /**
     * Where the original bytes, the woven bytes and a textual diff are written for each class the
     * weaver changed.
     *
     * <p>Set from the {@code aether.weaver.dump} property. It has no default: left unnamed the
     * field stays {@code null} and nothing is dumped. A class the weaver did not change is never
     * dumped, and a failure to write a dump is reported as {@code AW4090} rather than thrown, which
     * fails the build unless {@code failOnError} is cleared.
     */
    @Parameter(property = "aether.weaver.dump")
    private File dumpDirectory;

    /**
     * Creates a mojo whose parameters are not yet set.
     *
     * <p>Maven injects the {@code @Parameter} fields after construction, so until it does every
     * field holds its type's default except {@code failOnError}, which its initialiser sets.
     */
    protected AbstractWeaveMojo() {
        // Maven injects the @Parameter fields after construction.
    }

    /**
     * Returns the directory of compiled classes this goal rewrites in place.
     *
     * @return the directory, which need not exist; a run over a directory that does not exist does
     *         nothing
     */
    protected abstract File directory();

    /**
     * Returns the word naming this goal's classes in log lines and in the failure message.
     *
     * @return the description, such as {@code main} or {@code test}
     */
    protected abstract String describe();

    /**
     * Returns the classpath to search for declarations made by other artefacts.
     *
     * <p>Only the extension declarations found there are applied. A weave declared on the classpath
     * decides {@code AW3010} and is otherwise unused. Elements that do not exist on disk are
     * dropped before the search.
     *
     * @return the classpath elements, in the order they are to be searched
     * @throws MojoExecutionException if the classpath has not been resolved
     */
    @NotNull
    protected abstract List<String> classpathElements() throws MojoExecutionException;

    /**
     * Returns the classpath entries this project asked for by name.
     *
     * <p>An entry outside this set that declares a weave is reported as {@code AW3010}.
     *
     * @return the paths of the module's own output and of the dependencies it declares itself
     */
    @NotNull
    protected abstract Set<Path> directEntries();

    /**
     * Collects the directory a subclass rewrites and the files of the project's directly declared
     * dependencies.
     *
     * <p>A resolved artefact counts as direct when the project's own dependency list names its
     * group and artifact identifiers; the version is not compared, and an artefact whose file was
     * not resolved is left out. Everything else on the classpath arrived transitively.
     *
     * @param project the project whose declared dependencies decide, or {@code null} when there is
     *                none, in which case only {@code own} is returned
     * @param own     the directory the calling subclass supplies as its own — the module's main
     *                output directory for {@link WeaveMojo}, its test output directory for
     *                {@link WeaveTestsMojo} — or {@code null} to leave it out
     * @return the direct entries, which is empty when both arguments are {@code null}
     */
    @NotNull
    protected static Set<Path> directEntriesOf(@Nullable final MavenProject project,
                                               @Nullable final File own) {
        final Set<Path> direct = new HashSet<>();
        if (own != null) {
            direct.add(own.toPath());
        }
        if (project == null) {
            return direct;
        }
        final Set<String> declared = new HashSet<>();
        for (final Dependency dependency : project.getDependencies()) {
            declared.add(dependency.getGroupId() + ':' + dependency.getArtifactId());
        }
        for (final Artifact artifact : project.getArtifacts()) {
            if (artifact.getFile() != null
                    && declared.contains(artifact.getGroupId() + ':' + artifact.getArtifactId())) {
                direct.add(artifact.getFile().toPath());
            }
        }
        return direct;
    }

    /**
     * Builds the index of extension declarations visible to this module.
     *
     * <p>Reads every manifest on {@link #classpathElements()}, which is also where {@code AW3010}
     * is decided, and indexes the extensions against that same classpath so that a declaration
     * shadowed by a method the receiver or one of its supertypes really declares ({@code AW1309}),
     * or one contributing a call another declaration already contributes ({@code AW1308}), is
     * refused before anything is rewritten. Both have error severity, so either fails the build
     * unless {@code failOnError} is cleared.
     *
     * @param listener where the manifest and indexing diagnostics are collected
     * @return the index, or {@link ExtensionIndex#EMPTY} when the classpath declared no extension
     *         or none of them survived indexing
     * @throws MojoExecutionException if the classpath has not been resolved
     */
    @NotNull
    private ExtensionIndex extensions(@NotNull final DiagnosticListener listener)
            throws MojoExecutionException {
        final List<Path> classpath = Manifests.pathsOf(classpathElements());
        final WeaveManifest fromClasspath =
                Manifests.of(classpath, directEntries(), listener);
        if (fromClasspath.extensions().isEmpty()) {
            return ExtensionIndex.EMPTY;
        }
        return ExtensionIndex.of(fromClasspath.extensions(), new Receivers(classpath),
                listener::report);
    }

    /**
     * Runs the whole weave over {@link #directory()} and reports the result.
     *
     * <p>The order of the steps is described on the class. Nothing is written when the goal returns
     * early, and the closing line naming how many weaves ran and how many classes were rewritten is
     * printed only when the build was not failed.
     *
     * @throws MojoExecutionException if the classpath has not been resolved, or if any collected
     *                                diagnostic has error severity and {@code failOnError} is set
     * @throws UncheckedIOException   if a class file under the directory cannot be read or written
     */
    protected final void weaveDirectory() throws MojoExecutionException {
        if (this.skip) {
            getLog().info("Aether Weaver: skipped (aether.weaver.skip=true).");
            return;
        }

        final ClassDirectory classes = new ClassDirectory(directory().toPath());
        if (!classes.exists()) {
            getLog().debug("Aether Weaver: no " + describe() + " classes to weave.");
            return;
        }

        final Collected collected = new Collected();
        final ExtensionIndex extensions = extensions(collected);
        final WeaveManifest manifest = classes.manifest(collected);

        // No manifest is not an error. A module with no weaves is the overwhelmingly common
        // case, and failing it would make the plugin unusable in every module of a build but the
        // one or two that actually weave something.
        final List<WeaveClass> weaves = manifest == null
                ? List.of()
                : classes.weaves(manifest, collected);

        if (weaves.isEmpty() && extensions.isEmpty()) {
            getLog().debug("Aether Weaver: nothing to weave in " + describe() + " classes.");
            return;
        }
        if (weaves.isEmpty()) {
            // Extensions alone are a complete reason to run: this module calls them, and its call
            // sites are what has to be rewritten. Saying so at debug rather than info keeps the
            // ordinary case quiet, which is most modules of most builds.
            getLog().debug("Aether Weaver: no weaves here; rewriting extension calls only.");
        }

        final Weaver weaver = Weaver.builder()
                .weaves(weaves)
                .extensions(extensions)
                // The directory itself, rather than a map built from the weaves the manifest named:
                // the merge stage asks for whatever it needs, and a pre-built map can only answer
                // for what someone thought to put in it.
                .classSource(ClassSource.ofPath(classes.root()))
                .explain(this.explain)
                .diagnostics(collected)
                .build();

        final int changed = weave(classes, weaver, collected) + afterWeaving(weaver, collected);
        weaver.finish();

        final StatisticsView statistics = weaver.statistics();
        if (statistics.classesWoven() < statistics.plannedTargets()) {
            // A warning in a build, where it is not normal. At load time a planned target that
            // was never loaded is ordinary; here it means a weave did not apply to an artefact that
            // is about to be published, and nothing else in the build would say so.
            getLog().warn("Aether Weaver: " + (statistics.plannedTargets()
                    - statistics.classesWoven()) + " planned target"
                    + (statistics.plannedTargets() - statistics.classesWoven() == 1 ? "" : "s")
                    + " were not found in " + describe() + " classes ("
                    + statistics.classesWoven() + " of " + statistics.plannedTargets() + " woven)");
        }
        getLog().debug("Aether Weaver: " + statistics);
        if (this.explain) {
            // After weaving, not before. The plan is knowable up front; what each injection point
            // actually matched is not, and it is the half that answers "why did my handler not run".
            weaver.explain().lines().forEach(getLog()::info);
        }
        report(collected, changed, weaves.size());
    }

    /**
     * Weaves whatever else the subclass is responsible for, once the directory itself is done.
     *
     * <p>Called with the weaver that has just finished the directory and before anything is
     * reported, so a diagnostic raised here is logged and counted with the rest. This
     * implementation weaves nothing.
     *
     * @param weaver   the weaver that has just been used on the directory
     * @param listener the listener the directory's own diagnostics went to
     * @return the number of further classes written, added to the count reported at the end
     */
    protected int afterWeaving(@NotNull final Weaver weaver,
                               @NotNull final DiagnosticListener listener) {
        return 0;
    }

    /**
     * Weaves every class file in the directory and writes back the ones that changed.
     *
     * <p>A class the weaver refuses with a {@link WeaveException} is reported as {@code AW4090} and
     * left on disk untouched, and the loop continues with the next file. A class the weaver
     * returned no bytes for is left alone rather than rewritten with identical content.
     *
     * @param classes the directory being woven
     * @param weaver  the weaver every class is put through
     * @param report  where a refusal and any dump failure are recorded
     * @return the number of class files rewritten
     * @throws UncheckedIOException if a class file cannot be read or written
     */
    private int weave(@NotNull final ClassDirectory classes,
                      @NotNull final Weaver weaver,
                      @NotNull final Collected report) {
        final ClassDump dump = this.dumpDirectory == null
                ? null
                : new ClassDump(this.dumpDirectory.toPath());
        int changed = 0;
        for (final Path classFile : classes.classFiles()) {
            final String internalName = classes.internalNameOf(classFile);
            final byte[] woven;
            try {
                woven = weaver.weave(internalName, () -> readAll(classFile));
            } catch (final WeaveException refused) {
                report.report(Diagnostic.builder(DiagnosticCode.INTERNAL_ERROR)
                        .message(internalName + " could not be woven: " + refused.getMessage())
                        .build());
                continue;
            }
            if (woven == null) {
                // Untouched files are left alone rather than rewritten with identical content.
                // Rewriting would change every class file's modification time on every build, and
                // every downstream step that skips unchanged files would stop skipping.
                continue;
            }
            try {
                if (dump != null) {
                    // Read again rather than kept from the supplier above. The supplier is
                    // called at most once and only on a match, which is the whole point of it —
                    // holding every class's bytes so that a dump nobody enabled could use them
                    // would undo that for every build.
                    dump.write(internalName, readAll(classFile), woven, report);
                }
                Files.write(classFile, woven);
                changed++;
            } catch (final IOException failed) {
                throw new UncheckedIOException(failed);
            }
        }
        return changed;
    }

    /**
     * Logs everything that was collected and decides whether the build survives it.
     *
     * <p>Each diagnostic is formatted, degraded to what the charset of {@link System#out} can
     * encode, and logged at the level matching its severity. The closing summary is not reached
     * when the build is failed.
     *
     * @param collected the diagnostics gathered during the run
     * @param changed   how many class files were rewritten
     * @param weaves    how many weave classes the module's own manifest contributed
     * @throws MojoExecutionException if any collected diagnostic has error severity and
     *                                {@code failOnError} is set
     */
    private void report(@NotNull final Collected collected,
                        final int changed,
                        final int weaves) throws MojoExecutionException {
        for (final Diagnostic diagnostic : collected.diagnostics) {
            // Degraded for the stream Maven logs to: a console that cannot encode an arrow
            // writes a question mark instead, and a diagnostic full of them says nothing.
            final String text = ConsoleText.forStream(diagnostic.format(), System.out);
            switch (diagnostic.severity()) {
                case ERROR -> getLog().error(text);
                case WARNING -> getLog().warn(text);
                case INFO -> getLog().info(text);
                case DEBUG -> getLog().debug(text);
            }
        }

        final long errors = collected.diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity() == Severity.ERROR)
                .count();
        if (errors > 0 && this.failOnError) {
            throw new MojoExecutionException("Aether Weaver: " + errors + " error"
                    + (errors == 1 ? "" : "s") + " while weaving " + describe() + " classes");
        }
        getLog().info("Aether Weaver: " + weaves + " weave" + (weaves == 1 ? "" : "s")
                + ", " + changed + " class" + (changed == 1 ? "" : "es") + " rewritten ("
                + describe() + ").");
    }

    /**
     * Reads a class file whole.
     *
     * @param classFile the file to read
     * @return its bytes
     * @throws UncheckedIOException if the file cannot be read
     */
    private static byte[] readAll(@NotNull final Path classFile) {
        try {
            return Files.readAllBytes(classFile);
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * Holds every diagnostic of one run so that all of them can be logged together at the end.
     *
     * <p>Nothing is filtered, ordered or de-duplicated here: the list holds every diagnostic reported
     * through this listener over the course of the run, in the order each one arrived, regardless of
     * which part of the goal reported it.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class Collected implements DiagnosticListener {

        /** The diagnostics reported so far, in arrival order. */
        private final List<Diagnostic> diagnostics = new ArrayList<>();

        /**
         * Creates a collector holding no diagnostics.
         */
        Collected() {
            // Nothing to initialise beyond the list.
        }

        /**
         * Records a diagnostic for logging at the end of the run.
         *
         * @param diagnostic the diagnostic to record
         */
        @Override
        public void report(@NotNull final Diagnostic diagnostic) {
            this.diagnostics.add(diagnostic);
        }
    }
}
