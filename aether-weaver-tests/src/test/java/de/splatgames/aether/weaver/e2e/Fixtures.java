package de.splatgames.aether.weaver.e2e;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

final class Fixtures {

    private Fixtures() {
        throw new AssertionError("no instances");
    }

    static void compile(final Path output, final String... sources) {
        compile(output, List.of(), sources);
    }

    static void compile(final Path output, final List<Path> classpath, final String... sources) {
        final StringBuilder path = new StringBuilder(System.getProperty("java.class.path"));
        for (final Path extra : classpath) {
            path.append(java.io.File.pathSeparatorChar).append(extra);
        }

        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            final List<JavaFileObject> units = new ArrayList<>();
            for (final String source : sources) {
                units.add(new Source(pathOf(source), source));
            }
            assertThat(compiler.getTask(null, files, null,
                    // -proc:none, always. The weaver's own processor is on this module's classpath
                    // and would validate the fixtures — which is a different test's job, and which
                    // would make a fixture that is deliberately wrong fail to compile.
                    List.of("-classpath", path.toString(), "-proc:none"),
                    null, units).call())
                    .as("the fixtures must compile").isTrue();
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static String pathOf(final String source) {
        final Matcher pkg = Pattern.compile("(?m)^package (\\S+);").matcher(source);
        final Matcher type = Pattern.compile("(?m)^public (?:final )?class (\\w+)").matcher(source);
        assertThat(pkg.find() && type.find())
                .as("every fixture declares a package and one public class").isTrue();
        return pkg.group(1).replace('.', '/') + '/' + type.group(1);
    }

    private static final class Source extends SimpleJavaFileObject {

        private final String code;

        Source(final String path, final String code) {
            super(URI.create("string:///" + path + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return this.code;
        }
    }
}
