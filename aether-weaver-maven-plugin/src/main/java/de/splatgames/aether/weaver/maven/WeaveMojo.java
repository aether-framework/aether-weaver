package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.Weaver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Set;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Weaves the module's own compiled classes, and optionally the classes of its dependencies.
 *
 * <p>Bound to {@code process-classes} and resolving the compile and runtime scopes, so it runs on
 * the output of {@code javac} and before that output is packaged. What one run does is described on
 * {@link AbstractWeaveMojo}; this class supplies the directory to rewrite, the classpath to read
 * other artefacts' declarations from, and the dependency weaving that follows.
 *
 * <h2>Weaving dependencies</h2>
 *
 * <p>Off unless {@code weaveDependencies} is set. When it is, every resolved artefact of the
 * project that is an existing file whose name ends in {@code .jar} is put through the same weaver,
 * in path order, and each class the plan changed is written below
 * {@code dependencyOutputDirectory}. The artefacts themselves stay exactly as their publisher
 * shipped them, an unchanged class is not copied, and nothing places that directory on any
 * classpath; {@code AW2501} lists what was written and says what still has to be done with it. A
 * signed artefact is refused as {@code AW3002} unless {@code allowSigned} is set, which weaves it
 * and reports {@code AW3020} instead.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Mojo(name = "weave",
      defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
      threadSafe = true)
public class WeaveMojo extends AbstractWeaveMojo {

    /**
     * Creates a mojo whose parameters are not yet set.
     *
     * <p>Maven injects the {@code @Parameter} fields after construction.
     */
    public WeaveMojo() {
        // Maven injects the @Parameter fields after construction.
    }

    /**
     * The directory of compiled classes that is rewritten in place.
     *
     * <p>Read-only, so it cannot be configured: it is always the project's own output directory.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDirectory;

    /**
     * Whether the classes of resolved dependencies are woven as well.
     *
     * <p>Set from the {@code aether.weaver.weaveDependencies} property, {@code false} by default.
     * Left off, a weave whose target lives in a dependency never reaches it: the target stays
     * planned and unwoven.
     */
    @Parameter(property = "aether.weaver.weaveDependencies", defaultValue = "false")
    private boolean weaveDependencies;

    /**
     * Whether a signed dependency is woven rather than refused.
     *
     * <p>Set from the {@code aether.weaver.allowSigned} property, {@code false} by default. It has
     * no effect on its own: nothing reads it unless {@code weaveDependencies} is set as well.
     * Setting it turns the refusal {@code AW3002} into the override warning {@code AW3020}, which
     * is reported once for every signed artefact that is woven.
     */
    @Parameter(property = "aether.weaver.allowSigned", defaultValue = "false")
    private boolean allowSigned;

    /**
     * Where the woven copies of dependency classes are written.
     *
     * <p>It names no property, so it is set in the plugin's configuration rather than from the
     * command line, and it defaults to {@code aether-weaver/dependencies} under the build
     * directory. Only classes the plan changed appear there, at their own package paths, so
     * deleting the directory undoes the whole operation.
     */
    @Parameter(defaultValue = "${project.build.directory}/aether-weaver/dependencies")
    private File dependencyOutputDirectory;

    /**
     * The project whose compile classpath and resolved artefacts are read.
     *
     * <p>Read-only and required, so it is always the current project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Weaves the project's dependency jars once its own classes are done.
     *
     * <p>Does nothing at all unless {@code weaveDependencies} is set. The artefacts are sorted by
     * path before any of them is read, so two builds of one project report their dependencies in
     * the same order and the class list carried by {@code AW2501} can be diffed. An artefact whose
     * file was not resolved, is a directory, or does not end in {@code .jar} is left out.
     *
     * @param weaver   the weaver that has just been used on the module's own classes
     * @param listener where the dependency diagnostics go, alongside the directory's own
     * @return the number of dependency classes written
     */
    @Override
    protected int afterWeaving(@NotNull final Weaver weaver,
                               @NotNull final DiagnosticListener listener) {
        if (!this.weaveDependencies || this.project == null) {
            return 0;
        }
        final Path output = this.dependencyOutputDirectory.toPath();
        final List<Path> artefacts = new ArrayList<>();
        for (final Artifact artefact : this.project.getArtifacts()) {
            final File file = artefact.getFile();
            if (file != null && file.isFile() && file.getName().endsWith(".jar")) {
                artefacts.add(file.toPath());
            }
        }
        // Sorted, so that two builds of the same project report their dependencies in the same
        // order — the diagnostics list every modified class, and an unstable list is undiffable.
        artefacts.sort(Comparator.comparing(Path::toString));

        return new DependencyWeaver(weaver, output, this.allowSigned, listener).weave(artefacts);
    }

    /**
     * Returns the project's own output directory.
     *
     * @return the directory of compiled classes, which need not exist
     */
    @Override
    protected File directory() {
        return this.classesDirectory;
    }

    /**
     * Returns the project's compile classpath.
     *
     * @return the compile classpath elements, or an empty list when no project was injected
     * @throws MojoExecutionException if the compile classpath has not been resolved
     */
    @Override
    @NotNull
    protected List<String> classpathElements() throws MojoExecutionException {
        if (this.project == null) {
            return List.of();
        }
        try {
            return this.project.getCompileClasspathElements();
        } catch (final DependencyResolutionRequiredException unresolved) {
            throw new MojoExecutionException(
                    "the compile classpath is not resolved, so extensions cannot be found",
                    unresolved);
        }
    }

    /**
     * Returns the word naming these classes in log lines and in the failure message.
     *
     * @return {@code main}
     */
    @Override
    protected String describe() {
        return "main";
    }

    /**
     * Runs the weave over the project's output directory.
     *
     * @throws MojoExecutionException if the classpath has not been resolved, or if a diagnostic of
     *                                error severity was collected and {@code failOnError} is set
     */
    @Override
    public void execute() throws MojoExecutionException {
        weaveDirectory();
    }

    /**
     * Returns the output directory and the files of the dependencies this project declares itself.
     *
     * @return the entries a weave may arrive from without being reported as {@code AW3010}
     */
    @NotNull
    @Override
    protected Set<Path> directEntries() {
        return directEntriesOf(this.project, this.classesDirectory);
    }
}
