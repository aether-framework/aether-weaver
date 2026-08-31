package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.experimental.Scope;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.extension.ExtensionIndex;
import de.splatgames.aether.weaver.engine.extension.ExtensionStubs;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes the compile-time stubs that let a use of an extension compile.
 *
 * <p>An extension is a method or a constant one class contributes to a receiver it does not own.
 * The call site or the constant reference is rewritten later, by the {@code weave} goal, but the
 * source has to compile first, and it only does if the receiver appears to declare the member.
 * This goal takes each receiver's real class file, adds the contributed members to it — a method
 * stub for a method, a {@code public static final} field for a constant — and writes the result
 * where the compiler can be pointed at it. A stub is the receiver plus the extensions, never a
 * class carrying the extensions alone.
 *
 * <p>Bound to {@code generate-sources} and resolving the compile scope, so the stubs exist before
 * anything is compiled. The module's own classes are never touched; everything written goes below
 * {@code outputDirectory}.
 *
 * <h2>Where a stub lands</h2>
 *
 * <p>Two places, decided per receiver by whether the running JVM's runtime image holds a copy of
 * its class, not by where the bytes actually used for the stub were read from. A receiver the image
 * holds goes to {@code patch/}<i>module</i>{@code /}, even when a classpath entry that shadows the
 * image is what supplied the bytes; one the image does not hold goes to {@code classpath/}. The
 * {@code classpath/} directory is created on any run that gets as far as writing, even one that
 * placed nothing in it. Each {@code patch/}<i>module</i>{@code /} directory, by contrast, is created
 * only for a module that actually took a stub on this run: remove the last extension on a module and
 * its {@code patch/} directory is not created the next time the goal runs.
 *
 * <h2>Telling the compiler</h2>
 *
 * <p>The goal writes the stubs and prints, at info level, one {@code --patch-module} argument per
 * patched module and, only when a stub went into the classpath directory, that directory and how
 * many receivers it holds. It configures nothing itself: a build that does not pass those arguments
 * on compiles against the unpatched receivers.
 *
 * <h2>What is not stubbed</h2>
 *
 * <ul>
 *   <li>An extension a dependency declared with {@code Scope.MODULE}, unless this module's own
 *       output directory declares the same one. The count is logged at debug level.
 *   <li>A receiver that already declares every member contributed to it, which leaves nothing to
 *       add.
 *   <li>A receiver found neither on the compile classpath nor in the runtime image. When any
 *       declaration on it is required the goal fails; when all of them are optional it is skipped
 *       with a line at debug level.
 * </ul>
 *
 * <h2>Diagnostics</h2>
 *
 * <p>Everything the manifest reader and the extension index report arrives on one listener that
 * logs the code and the message, at error level for an error and at warning level for every other
 * severity. The details and the remedy of a diagnostic are not printed, and none of them fails the
 * goal: a declaration refused as {@code AW1308}, because two declarations contribute the same
 * call, or as {@code AW1309}, because the receiver or something it inherits from really declares
 * that method, is logged and then simply not stubbed. A manifest on the classpath that cannot be
 * parsed arrives the same way as {@code AW2300}, and one written by a newer release as
 * {@code AW2301}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Mojo(name = "stubs",
      defaultPhase = LifecyclePhase.GENERATE_SOURCES,
      requiresDependencyResolution = ResolutionScope.COMPILE,
      threadSafe = true)
public class StubsMojo extends AbstractMojo {

    /** The subdirectory holding one directory per module of the runtime image that took a stub. */
    static final String PATCH = "patch";

    /** The subdirectory holding the receivers the runtime image does not hold. */
    static final String CLASSPATH = "classpath";

    /**
     * Creates a mojo whose parameters are not yet set.
     *
     * <p>Maven injects the {@code @Parameter} fields after construction.
     */
    public StubsMojo() {
        // Maven injects the @Parameter fields after construction.
    }

    /**
     * Whether the goal returns without looking at anything.
     *
     * <p>Set from the {@code aether.weaver.skip} property, {@code false} by default. The weaving
     * goals read the same property, so setting it on the command line disables all of them at once.
     */
    @Parameter(property = "aether.weaver.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Where the stub tree is written.
     *
     * <p>It names no property, so it is set in the plugin's configuration rather than from the
     * command line, and it defaults to {@code aether-weaver/stubs} under the build directory. The
     * goal creates it lazily: a module whose classpath declares no extension leaves no directory
     * behind at all.
     */
    @Parameter(defaultValue = "${project.build.directory}/aether-weaver/stubs")
    private File outputDirectory;

    /**
     * The project whose compile classpath is searched, and whose output directory decides which
     * module-scoped extensions are this module's own.
     *
     * <p>Read-only and required, so it is always the current project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Writes a stub for every receiver the compile classpath contributes an extension to.
     *
     * <p>Returns after one line at info level when {@code skip} is set, and after one line at debug
     * level, having created nothing at all, when nothing on the classpath declares an extension
     * that survives indexing. Otherwise it writes the stubs, logs how many extension methods went
     * into how many classes, and, when at least one stub was actually placed, prints the compiler
     * arguments they need.
     *
     * @throws MojoExecutionException if the compile classpath has not been resolved, if a receiver
     *                                that a required extension names is on neither the classpath
     *                                nor the runtime image, or if a stub cannot be written
     */
    @Override
    public void execute() throws MojoExecutionException {
        if (this.skip) {
            getLog().info("Aether Weaver: stub generation skipped (aether.weaver.skip=true).");
            return;
        }

        final List<Path> classpath;
        try {
            classpath = Manifests.pathsOf(this.project.getCompileClasspathElements());
        } catch (final org.apache.maven.artifact.DependencyResolutionRequiredException unresolved) {
            throw new MojoExecutionException(
                    "the compile classpath is not resolved, so extensions cannot be found",
                    unresolved);
        }

        final DiagnosticListener listener = diagnostic -> {
            if (diagnostic.severity() == Severity.ERROR) {
                getLog().error(render(diagnostic));
            } else {
                getLog().warn(render(diagnostic));
            }
        };

        final WeaveManifest manifest = Manifests.of(classpath, listener);
        final Receivers receivers = new Receivers(classpath);
        final ExtensionIndex index = ExtensionIndex.of(
                visible(manifest, ownOutput(classpath), listener), receivers, listener::report);
        if (index.isEmpty()) {
            getLog().debug("Aether Weaver: no extensions on the compile classpath.");
            return;
        }

        final Path root = this.outputDirectory.toPath();
        final Map<String, Path> patched = new LinkedHashMap<>();
        final List<String> onClasspath = new ArrayList<>();
        int written = 0;

        try {
            for (final String receiver : index.receivers()) {
                final Optional<byte[]> original = receivers.find(receiver);
                if (original.isEmpty()) {
                    // Not on the compile classpath and not in the runtime image. javac will refuse
                    // the call site in a moment and say so far better than this could, but it would
                    // blame the caller — so the cause is named here, where it is known, and the
                    // declaration decides whether that is a failure or an arrangement.
                    missing(receiver, index.contributedTo(receiver));
                    continue;
                }

                final byte[] stub = ExtensionStubs.patch(original.get(),
                        index.contributedTo(receiver), receivers);
                if (stub == null) {
                    continue;
                }

                final String module = receivers.moduleOf(receiver);
                final Path directory = module == null
                        ? root.resolve(CLASSPATH)
                        : root.resolve(PATCH).resolve(module);
                if (module == null) {
                    onClasspath.add(receiver);
                } else {
                    patched.put(module, directory);
                }

                final Path file = directory.resolve(receiver + ".class");
                Files.createDirectories(file.getParent());
                Files.write(file, stub);
                written++;
            }

            // Created whether or not anything went in them, so that a --patch-module or a classpath
            // entry naming one keeps working after the last extension on it is removed.
            Files.createDirectories(root.resolve(CLASSPATH));
            for (final Path directory : patched.values()) {
                Files.createDirectories(directory);
            }
        } catch (final IOException | UncheckedIOException failed) {
            throw new MojoExecutionException("a compile-time stub could not be written to " + root,
                    failed);
        }

        getLog().info("Aether Weaver: " + index.size() + " extension method"
                + (index.size() == 1 ? "" : "s") + " stubbed into " + written + " class"
                + (written == 1 ? "" : "es") + ".");
        advise(patched, onClasspath, root);
    }

    /**
     * Prints what the compiler still has to be told, at info level.
     *
     * <p>One {@code --patch-module} pair per module that took a stub, then, only when the classpath
     * directory itself received a stub, that directory and the number of receivers behind it.
     * Nothing at all is printed when no stub was placed anywhere.
     *
     * @param patched     the directory each patched module's stubs went into, by module name
     * @param onClasspath the receivers stubbed into the classpath directory
     * @param root        the stub tree's root
     */
    private void advise(@NotNull final Map<String, Path> patched,
                        @NotNull final List<String> onClasspath,
                        @NotNull final Path root) {
        if (patched.isEmpty() && onClasspath.isEmpty()) {
            return;
        }
        getLog().info("Aether Weaver: the compiler must be told where the stubs are:");
        for (final Map.Entry<String, Path> each : patched.entrySet()) {
            getLog().info("    <arg>--patch-module</arg>");
            getLog().info("    <arg>" + each.getKey() + "=" + each.getValue() + "</arg>");
        }
        if (!onClasspath.isEmpty()) {
            getLog().info("    and " + root.resolve(CLASSPATH)
                    + " ahead of the dependencies on the compile classpath, for "
                    + onClasspath.size() + " receiver" + (onClasspath.size() == 1 ? "" : "s"));
        }
    }

    /**
     * Formats a diagnostic as the one line this goal logs for it.
     *
     * @param diagnostic the diagnostic to render
     * @return a literal prefix, its code and its message; the details and the remedy are dropped
     */
    @NotNull
    private static String render(@NotNull final Diagnostic diagnostic) {
        return "Aether Weaver: " + diagnostic.code().code() + ' ' + diagnostic.message();
    }
    /**
     * Drops the extensions a dependency keeps to its own module.
     *
     * <p>A declaration whose scope is {@code MODULE} is stubbed only when this module's own output
     * declares the very same one, compared as a whole declaration rather than by receiver or by
     * name. Every other scope passes. What was withheld is counted and logged at debug level rather
     * than reported as a diagnostic.
     *
     * @param everything every declaration found anywhere on the compile classpath
     * @param own        the classpath entries that are this module's own output
     * @param listener   where reading the module's own manifest reports
     * @return the declarations to stub, in the order the merged manifest held them
     */
    @NotNull
    private List<WeaveManifest.Extension> visible(@NotNull final WeaveManifest everything,
                                                  @NotNull final List<Path> own,
                                                  @NotNull final DiagnosticListener listener) {
        final List<WeaveManifest.Extension> mine = Manifests.of(own, listener).extensions();
        final List<WeaveManifest.Extension> visible = new ArrayList<>();

        int withheld = 0;
        for (final WeaveManifest.Extension extension : everything.extensions()) {
            if (extension.scope() != Scope.MODULE || mine.contains(extension)) {
                visible.add(extension);
                continue;
            }
            withheld++;
        }
        if (withheld > 0) {
            getLog().debug("Aether Weaver: " + withheld + " extension(s) declared by dependencies "
                    + "are scoped to their own module and were not stubbed.");
        }
        return visible;
    }

    /**
     * Picks out the classpath entries that are this module's own output.
     *
     * <p>An entry qualifies by being equal to the path the project states as its output directory,
     * so a directory that merely contains it does not count.
     *
     * @param classpath the compile classpath
     * @return the entries that are the module's own output, which is empty when the project states
     *         no build or no output directory; nothing then counts as own, and every module-scoped
     *         extension on the classpath is a dependency's
     */
    @NotNull
    private List<Path> ownOutput(@NotNull final List<Path> classpath) {
        final String directory = this.project.getBuild() == null
                ? null
                : this.project.getBuild().getOutputDirectory();
        if (directory == null) {
            // Outside a real Maven injection there is no output directory to ask about, and
            // claiming an entry as the project's own would be inventing a permission. Nothing is
            // own, so every MODULE extension on the classpath is a dependency's.
            return List.of();
        }
        final Path output = Path.of(directory);
        final List<Path> own = new ArrayList<>();
        for (final Path entry : classpath) {
            if (entry.equals(output)) {
                own.add(entry);
            }
        }
        return own;
    }

    /**
     * Decides what happens to a receiver that could not be found.
     *
     * <p>No stub can be made for a receiver that is on neither the compile classpath nor the
     * runtime image. A declaration that requires it fails the goal here, where the absent type can
     * still be named, and the message lists each requiring holder once and says how to proceed:
     * add the dependency that provides the receiver, or declare the extension optional. When every
     * declaration on that receiver is optional it is skipped with a line at debug level, which is
     * what lets an extension depend softly on an artefact the consumer may not have.
     *
     * @param receiver     the receiver's internal name
     * @param declarations everything contributed to that receiver
     * @throws MojoExecutionException if any of those declarations requires the receiver
     */
    private void missing(@NotNull final String receiver,
                         @NotNull final List<WeaveManifest.Extension> declarations)
            throws MojoExecutionException {
        final List<String> requiring = new ArrayList<>();
        for (final WeaveManifest.Extension declaration : declarations) {
            if (declaration.require() == Require.REQUIRED
                    && !requiring.contains(declaration.className())) {
                requiring.add(declaration.className());
            }
        }
        if (requiring.isEmpty()) {
            getLog().debug("Aether Weaver: " + receiver.replace('/', '.')
                    + " is not on the compile classpath; its optional extensions were skipped.");
            return;
        }
        throw new MojoExecutionException(receiver.replace('/', '.')
                + " is extended by " + String.join(", ", requiring)
                + " but is not on the compile classpath, so no stub can be produced for it and "
                + "every call naming it will fail to compile. Add the dependency that provides it, "
                + "or declare the extension @Extension(require = Require.OPTIONAL).");
    }

}
