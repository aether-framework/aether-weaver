package de.splatgames.aether.weaver.maven;

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
import java.util.List;

/**
 * Weaves the module's compiled test classes.
 *
 * <p>Bound to {@code process-test-classes} and resolving the test scope, so it runs on the output
 * of the test compiler and before the tests are run. What one run does is described on
 * {@link AbstractWeaveMojo}; this class supplies the test output directory and the test classpath,
 * which is where a weave that only ever applies to a test fixture belongs.
 *
 * <p>It leaves {@code afterWeaving} alone, so nothing outside the test output directory is written:
 * dependency weaving is the {@code weave} goal's alone.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Mojo(name = "weave-tests",
      defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
      requiresDependencyResolution = ResolutionScope.TEST,
      threadSafe = true)
public class WeaveTestsMojo extends AbstractWeaveMojo {

    /**
     * Creates a mojo whose parameters are not yet set.
     *
     * <p>Maven injects the {@code @Parameter} fields after construction.
     */
    public WeaveTestsMojo() {
        // Maven injects the @Parameter fields after construction.
    }

    /**
     * The directory of compiled test classes that is rewritten in place.
     *
     * <p>Read-only, so it cannot be configured: it is always the project's own test output
     * directory.
     */
    @Parameter(defaultValue = "${project.build.testOutputDirectory}", readonly = true)
    private File testClassesDirectory;

    /**
     * The project whose test classpath is read.
     *
     * <p>Read-only and required, so it is always the current project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Returns the project's own test output directory.
     *
     * @return the directory of compiled test classes, which need not exist
     */
    @Override
    protected File directory() {
        return this.testClassesDirectory;
    }

    /**
     * Returns the project's test classpath.
     *
     * @return the test classpath elements, or an empty list when no project was injected
     * @throws MojoExecutionException if the test classpath has not been resolved
     */
    @Override
    @NotNull
    protected List<String> classpathElements() throws MojoExecutionException {
        if (this.project == null) {
            return List.of();
        }
        try {
            return this.project.getTestClasspathElements();
        } catch (final DependencyResolutionRequiredException unresolved) {
            throw new MojoExecutionException(
                    "the test classpath is not resolved, so extensions cannot be found",
                    unresolved);
        }
    }

    /**
     * Returns the word naming these classes in log lines and in the failure message.
     *
     * @return {@code test}
     */
    @Override
    protected String describe() {
        return "test";
    }

    /**
     * Runs the weave over the project's test output directory.
     *
     * @throws MojoExecutionException if the classpath has not been resolved, or if a diagnostic of
     *                                error severity was collected and {@code failOnError} is set
     */
    @Override
    public void execute() throws MojoExecutionException {
        weaveDirectory();
    }

    /**
     * Returns the test output directory and the classpath entries of this project's declared
     * dependencies.
     *
     * <p>An entry outside this set that declares a weave is reported as {@code AW3010}. This set
     * does not include the project's own main output directory, which the test classpath carries as
     * a separate entry from the test output directory returned here: a weave declared in the
     * module's own main classes is therefore reported by this goal as though it arrived as a
     * dependency of a dependency.
     *
     * @return the entries a weave in the test classpath must belong to in order not to be reported
     *         as {@code AW3010}
     */
    @NotNull
    @Override
    protected Set<Path> directEntries() {
        return directEntriesOf(this.project, this.testClassesDirectory);
    }
}
