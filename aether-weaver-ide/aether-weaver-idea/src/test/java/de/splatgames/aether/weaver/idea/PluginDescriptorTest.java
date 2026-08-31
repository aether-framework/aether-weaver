package de.splatgames.aether.weaver.idea;

import junit.framework.TestCase;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PluginDescriptorTest extends TestCase {

    private static final String DESCRIPTOR = "/META-INF/plugin.xml";

    private static final Pattern IMPLEMENTATION = Pattern.compile(
            "(?:implementation(?:Class)?|factoryClass)=\"([^\"]+)\"|<className>([^<]+)</className>");

    private static final Pattern ACTION =
            Pattern.compile("<action\\s[^>]*?class=\"([^\"]+)\"");

    private static final Pattern SHORT_NAME = Pattern.compile("shortName=\"([^\"]+)\"");

    private static final Pattern INTENTION_CLASS =
            Pattern.compile("<className>([^<]+)</className>");

    private static final int REGISTERED_EXTENSIONS = 31;

    private static final int REGISTERED_ACTIONS = 2;

    public void testEveryRegisteredClassExists() throws Exception {
        final List<String> named = allMatches(IMPLEMENTATION, descriptor());

        assertEquals("the descriptor registers a different number of extensions than this test "
                        + "knows about. That is not a failure by itself — but nothing else in this "
                        + "suite reads plugin.xml, so a new extension has to be looked at here "
                        + "once: " + named,
                REGISTERED_EXTENSIONS, named.size());
        for (final String className : named) {
            try {
                Class.forName(className, false, PluginDescriptorTest.class.getClassLoader());
            } catch (final ClassNotFoundException absent) {
                fail("plugin.xml registers '" + className + "', which does not exist. The IDE would "
                        + "report this in a log at startup and the feature would simply be missing");
            }
        }
    }

    public void testEveryRegisteredActionExists() throws Exception {
        final List<String> named = allMatches(ACTION, descriptor());

        assertEquals("the descriptor registers a different number of actions than this test knows "
                        + "about: " + named,
                REGISTERED_ACTIONS, named.size());
        for (final String className : named) {
            try {
                Class.forName(className, false, PluginDescriptorTest.class.getClassLoader());
            } catch (final ClassNotFoundException absent) {
                fail("plugin.xml registers the action '" + className + "', which does not exist. "
                        + "The menu entry would simply be missing");
            }
        }
    }

    public void testEveryInspectionHasItsDescription() throws Exception {
        final List<String> shortNames = allMatches(SHORT_NAME, descriptor());

        assertFalse("the plugin ships inspections; finding none here means this test stopped "
                + "reading the descriptor rather than that the descriptor stopped having them",
                shortNames.isEmpty());
        for (final String shortName : shortNames) {
            final String path = "/inspectionDescriptions/" + shortName + ".html";
            assertNotNull("inspection '" + shortName + "' has no description at " + path
                            + "; the settings screen would show an empty panel where the "
                            + "explanation of a red underline belongs",
                    PluginDescriptorTest.class.getResource(path));
        }
    }

    public void testEveryIntentionHasItsDescription() throws Exception {
        final List<String> classNames = allMatches(INTENTION_CLASS, descriptor());

        assertFalse("the plugin ships intentions; finding none here means this test stopped "
                + "reading the descriptor rather than that the descriptor stopped having them",
                classNames.isEmpty());
        for (final String className : classNames) {
            final String directory =
                    "/intentionDescriptions/" + className.substring(className.lastIndexOf('.') + 1);
            for (final String file
                    : List.of("description.html", "before.java.template", "after.java.template")) {
                assertNotNull("intention '" + className + "' has no " + file + " at " + directory
                                + "; the settings screen would list it with nothing to read",
                        PluginDescriptorTest.class.getResource(directory + '/' + file));
            }
        }
    }

    private static String descriptor() throws IOException {
        try (InputStream source = PluginDescriptorTest.class.getResourceAsStream(DESCRIPTOR)) {
            assertNotNull("the plugin descriptor is not on the test classpath", source);
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> allMatches(final Pattern pattern, final String text) {
        final List<String> found = new ArrayList<>();
        final Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            // Alternations leave the unmatched branch null; SHORT_NAME has only one group, so the
            // second is asked for only when the pattern actually has one.
            String value = matcher.group(1);
            if (value == null && matcher.groupCount() >= 2) {
                value = matcher.group(2);
            }
            found.add(value);
        }
        return found;
    }
}
