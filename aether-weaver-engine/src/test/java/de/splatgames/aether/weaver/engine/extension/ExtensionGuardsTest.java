package de.splatgames.aether.weaver.engine.extension;

import de.splatgames.aether.weaver.api.experimental.Nulls;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.experimental.Scope;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.engine.extension.fixture.GreetingExtensions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionGuardsTest {

    private static final String HOLDER =
            "de.splatgames.aether.weaver.engine.extension.fixture.GreetingExtensions";

    private static final String GREETING =
            "de.splatgames.aether.weaver.engine.extension.fixture.Greeting";

    private static final WeaveManifest.Extension CHECKED = new WeaveManifest.Extension(
            HOLDER, GREETING, "shout", "(I)Ljava/lang/String;",
            WeaveManifest.Extension.Kind.INSTANCE, Require.REQUIRED, Nulls.CHECKED, Scope.PUBLIC);

    private static final WeaveManifest.Extension NULLABLE = new WeaveManifest.Extension(
            HOLDER, GREETING, "shout", "(I)Ljava/lang/String;",
            WeaveManifest.Extension.Kind.INSTANCE, Require.REQUIRED, Nulls.NULLABLE, Scope.PUBLIC);

    @Nested
    @DisplayName("what a checked receiver does when it is null")
    class Checked {

        @Test
        @DisplayName("the call throws, naming the extension")
        void nullReceiverThrows(@TempDir final Path directory) throws Exception {
            final byte[] hardened = ExtensionGuards.harden(bytesOf(), List.of(CHECKED));
            assertThat(hardened).as("the declaration asks for a guard, so one must be added")
                    .isNotNull();

            assertThatThrownBy(() -> invokeShout(directory, hardened, null))
                    .isInstanceOf(NullPointerException.class)
                    .as("a message that does not name the extension leaves the reader hunting for "
                            + "a receiver they cannot see in the stack trace")
                    .hasMessageContaining("shout")
                    .hasMessageContaining(HOLDER);
        }

        @Test
        @DisplayName("and an ordinary call still returns what it always did")
        void anOrdinaryCallIsUntouched(@TempDir final Path directory) throws Exception {
            final byte[] hardened = ExtensionGuards.harden(bytesOf(), List.of(CHECKED));

            assertThat(invokeShout(directory, hardened, newGreeting()))
                    .as("a guard that changed the answer would be a rewrite, not a check")
                    .isEqualTo("HELLO WORLD! HELLO WORLD!");
        }
    }

    @Nested
    @DisplayName("what is deliberately left alone")
    class Untouched {

        @Test
        @DisplayName("an unchecked or nullable declaration is not woven at all")
        void nothingIsWovenWithoutTheDeclaration() {
            assertThat(ExtensionGuards.harden(bytesOf(), List.of(NULLABLE)))
                    .as("NULLABLE is the opposite promise, and weaving a check into it would refuse "
                            + "the very calls the author wrote the method for")
                    .isNull();
            assertThat(ExtensionGuards.harden(bytesOf(),
                    List.of(new WeaveManifest.Extension(HOLDER, GREETING, "shout",
                            "(I)Ljava/lang/String;"))))
                    .as("and the default is what every extension written before this element "
                            + "existed already meant")
                    .isNull();
        }

        @Test
        @DisplayName("hardening an already-hardened class adds nothing")
        void itIsIdempotent() {
            final byte[] once = ExtensionGuards.harden(bytesOf(), List.of(CHECKED));
            assertThat(once).isNotNull();

            assertThat(ExtensionGuards.harden(once, List.of(CHECKED)))
                    .as("there is no stamp to consult, so the method's own first instructions are "
                            + "what stop a second check being stacked behind the first")
                    .isNull();
        }

        @Test
        @DisplayName("a class that declares nothing is not rebuilt")
        void aClassThatDeclaresNothingIsUntouched() {
            assertThat(ExtensionGuards.harden(bytesOf(), List.of()))
                    .as("every class an agent loads passes through here")
                    .isNull();
        }
    }

    // --- the machinery ---------------------------------------------------------------------------

    private static byte[] bytesOf() {
        final String resource = '/' + GreetingExtensions.class.getName().replace('.', '/') + ".class";
        try (InputStream in = ExtensionGuardsTest.class.getResourceAsStream(resource)) {
            assertThat(in).isNotNull();
            return in.readAllBytes();
        } catch (final IOException unreadable) {
            throw new AssertionError(unreadable);
        }
    }

    private static Object newGreeting() throws Exception {
        return Class.forName(GREETING).getConstructor(String.class).newInstance("world");
    }

    private static String invokeShout(final Path directory, final byte[] hardened,
                                      final Object receiver) throws Exception {
        final Path file = directory.resolve(HOLDER.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, hardened);

        final List<URL> urls = new ArrayList<>();
        urls.add(directory.toUri().toURL());
        for (final String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toURL());
        }

        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null)) {
            final Class<?> greeting = Class.forName(GREETING, true, loader);
            final Method shout = Class.forName(HOLDER, true, loader)
                    .getMethod("shout", greeting, int.class);
            final Object argument = receiver == null
                    ? null
                    : greeting.getConstructor(String.class).newInstance("world");
            try {
                return (String) shout.invoke(null, argument, 2);
            } catch (final InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof final RuntimeException thrown) {
                    throw thrown;
                }
                throw wrapped;
            }
        }
    }
}
