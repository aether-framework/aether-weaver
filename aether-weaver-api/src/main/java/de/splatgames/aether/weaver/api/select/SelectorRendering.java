package de.splatgames.aether.weaver.api.select;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.constant.ClassDesc;

/**
 * The spelling rules shared by the parser and by every selector's rendering.
 *
 * <p>Four callers depend on these rules agreeing: the three selector implementations, which render text, and
 * {@code SelectorParser}, which reads it back. A rendering that spelled a type differently from the way the parser
 * reads it would produce selectors that do not survive being rendered and parsed again. Keeping the rules in one
 * place is what makes that agreement structural rather than a coincidence.
 *
 * <p>Stateless, and every method is a pure function of its arguments.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see MemberSelector
 * @see TypePattern
 */
final class SelectorRendering {

    /**
     * Prevents instantiation.
     */
    private SelectorRendering() {
    }

    /**
     * Renders a type the way a class file names an owner.
     *
     * <p>An array owner is a full descriptor and a class owner is an internal name -- {@code [Ljava/lang/String;}
     * against {@code java/lang/String} -- which is the distinction a class file itself makes, since
     * {@code [Ljava/lang/String;} is a legal owner of {@code clone()}. A primitive has no internal name and falls
     * through to its descriptor.
     *
     * @param type the type to render; must not be {@code null}
     * @return the internal name for a class, and the descriptor for an array or a primitive
     */
    static String internalName(@NotNull final ClassDesc type) {
        if (type.isArray()) {
            return type.descriptorString();
        }
        final String descriptor = type.descriptorString();
        return descriptor.startsWith("L") && descriptor.endsWith(";")
                ? descriptor.substring(1, descriptor.length() - 1)
                : descriptor;
    }

    /**
     * Renders a type the way Java source names it.
     *
     * <p>An array appends {@code []} per dimension, a primitive renders as its keyword, and a class renders with
     * its package, or bare when it is in the unnamed package. A nested class keeps the {@code $} its binary name
     * carries, because the parser reads the result back through the same grammar and {@code $} is an identifier
     * character there.
     *
     * @param type the type to render; must not be {@code null}
     * @return the source spelling of the type
     */
    static String sourceName(@NotNull final ClassDesc type) {
        if (type.isArray()) {
            return sourceName(type.componentType()) + "[]";
        }
        if (type.isPrimitive()) {
            return type.displayName();
        }
        final String packageName = type.packageName();
        return packageName.isEmpty() ? type.displayName() : packageName + '.' + type.displayName();
    }

    /**
     * Reports whether a name is one the source grammar accepts for a member.
     *
     * <p>A Java identifier, or one of three names that are not identifiers and are needed anyway: {@code *} for
     * every member, and {@code <init>} and {@code <clinit>} for the two initialisers a class file declares. The
     * wildcard counts only as the whole name, so {@code get*} is refused and no selector carries a partial
     * pattern.
     *
     * @param name the name to check; must not be {@code null}
     * @return whether the source grammar accepts it as a member name
     */
    static boolean isValidMemberName(@NotNull final String name) {
        if (name.isEmpty()) {
            return false;
        }
        if ("*".equals(name) || "<init>".equals(name) || "<clinit>".equals(name)) {
            return true;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a name is one the source grammar accepts for an owner.
     *
     * <p>A run of Java identifiers separated by single dots: no leading dot, no trailing dot and no empty segment
     * between two. A simple name is accepted, since an owner may be written the way the surrounding source file
     * writes it. Slashes are not, so an internal name pasted into the source form is refused rather than read as a
     * strange class name.
     *
     * @param name the name to check; must not be {@code null}
     * @return whether the source grammar accepts it as an owner name
     */
    static boolean isValidOwnerName(@NotNull final String name) {
        if (name.isEmpty() || name.startsWith(".") || name.endsWith(".") || name.contains("..")) {
            return false;
        }
        for (final String segment : name.split("\\.", -1)) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) {
                return false;
            }
            for (int i = 1; i < segment.length(); i++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns the descriptor of a pattern that has one.
     *
     * <p>Only a {@link TypePattern.Exact} does. The {@code null} is what makes a descriptor rendering give up as a
     * whole: a selector holding one unresolved type cannot be written in a form that names exactly one member, and
     * every caller turns this answer into a fallback to the source rendering.
     *
     * @param pattern the pattern to render; must not be {@code null}
     * @return the descriptor string, or {@code null} for a wildcard or an unresolved name
     */
    @Nullable
    static String descriptorOrNull(@NotNull final TypePattern pattern) {
        return pattern instanceof TypePattern.Exact(ClassDesc type)
                ? type.descriptorString()
                : null;
    }
}
