package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reduces a weave class's own fields and methods to the entries the manifest records for them.
 *
 * <p>This is the compile-time half of what the runtime later reads back: for each member that is
 * not a handler, how it relates to the target, what it is called on both sides, and its JVM
 * descriptor. Nothing here is a check — no diagnostic is reported and no member is refused — so a
 * weave whose members were all rejected still produces entries for them.
 *
 * <p>Descriptors are assembled from the compiler's type mirrors rather than read out of a class
 * file, which is what makes them available before the weave has been compiled. The assembly is not
 * exact for every type: see {@link #typeDescriptorOf(TypeMirror)}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class SourceMembers {

    /**
     * Refuses instantiation; every entry point is static.
     *
     * @throws AssertionError always
     */
    private SourceMembers() {
        throw new AssertionError("no instances");
    }

    /**
     * Lists the weave's non-handler members in the order the compiler enumerates them.
     *
     * <p>Only fields and methods are considered; a constructor or a nested type is skipped on its
     * kind, and an enum constant is a field and so is included. A method carrying {@code @Inject},
     * {@code @Inject.Container}, {@code @Redirect} or {@code @Wrap} is skipped as well: a handler is
     * recorded as an injector instead, so that one method never appears twice under two spellings.
     *
     * <p>Each entry carries the member's disposition, {@code "FIELD"} or {@code "METHOD"}, its name
     * in the weave, its descriptor, the name it will have in the target, and whether it is declared
     * {@code @Unique}.
     *
     * @param weave the weave class; must not be {@code null}
     * @return the entries, in declaration order, as an unmodifiable list
     * @throws NullPointerException if {@code weave} is {@code null}
     */
    @NotNull
    @Unmodifiable
    static List<WeaveManifest.Member> of(@NotNull final TypeElement weave) {
        Objects.requireNonNull(weave, "weave");
        final List<WeaveManifest.Member> members = new ArrayList<>();

        for (final Element member : weave.getEnclosedElements()) {
            final boolean isField = member.getKind().isField();
            if (!isField && member.getKind() != ElementKind.METHOD) {
                continue;
            }
            if (member instanceof ExecutableElement method && WeaveProcessor.isHandler(method)) {
                continue;
            }
            final String disposition = dispositionOf(member);
            members.add(new WeaveManifest.Member(
                    disposition,
                    isField ? "FIELD" : "METHOD",
                    member.getSimpleName().toString(),
                    descriptorOf(member),
                    targetNameOf(member, disposition),
                    Anchors.mirrorOf(member, WeaveProcessor.UNIQUE) != null));
        }
        return List.copyOf(members);
    }

    /**
     * Names how a member relates to the target.
     *
     * <p>{@code @Shadow}, {@code @Accessor} and {@code @Invoker} are looked for in that order and
     * the first found wins, so a member carrying two of them reads as the earlier one and the
     * second annotation leaves no trace in the manifest. A member carrying none of the three is a
     * merged member.
     *
     * @param member the member to classify; must not be {@code null}
     * @return {@code "SHADOW"}, {@code "ACCESSOR"}, {@code "INVOKER"} or {@code "MERGE"}
     */
    @Contract(pure = true)
    @NotNull
    private static String dispositionOf(@NotNull final Element member) {
        if (Anchors.mirrorOf(member, WeaveProcessor.SHADOW) != null) {
            return "SHADOW";
        }
        if (Anchors.mirrorOf(member, WeaveProcessor.ACCESSOR) != null) {
            return "ACCESSOR";
        }
        if (Anchors.mirrorOf(member, WeaveProcessor.INVOKER) != null) {
            return "INVOKER";
        }
        return "MERGE";
    }

    /**
     * Reports the name the member will have in the target.
     *
     * <p>A merged member keeps the name it is declared under. For the other three dispositions the
     * annotation's {@code value} is used where it is not blank, whatever the disposition — so a
     * {@code @Shadow("name")} renames as much as an {@code @Accessor("name")} does.
     *
     * <p>An {@code @Accessor} or {@code @Invoker} that names nothing falls back to inference from
     * the declared name; a {@code @Shadow} that names nothing does not, and keeps the declared
     * name.
     *
     * @param member      the member; must not be {@code null}
     * @param disposition the string {@code dispositionOf} returned for it; must not be
     *                    {@code null}
     * @return the target-side name, which equals the declared name unless a {@code value} was
     *         written or a prefix was inferred away
     */
    @Contract(pure = true)
    @NotNull
    private static String targetNameOf(@NotNull final Element member,
                                       @NotNull final String disposition) {
        final String declared = member.getSimpleName().toString();
        final AnnotationMirror mirror = switch (disposition) {
            case "SHADOW" -> Anchors.mirrorOf(member, WeaveProcessor.SHADOW);
            case "ACCESSOR" -> Anchors.mirrorOf(member, WeaveProcessor.ACCESSOR);
            case "INVOKER" -> Anchors.mirrorOf(member, WeaveProcessor.INVOKER);
            default -> null;
        };
        if (mirror == null) {
            return declared;
        }
        final String named = Anchors.stringOf(mirror, "value", "");
        if (!named.isBlank()) {
            return named;
        }
        return switch (disposition) {
            case "ACCESSOR" -> inferred(declared, List.of("get", "set", "is"));
            case "INVOKER" -> inferred(declared, List.of("call", "invoke"));
            default -> declared;
        };
    }

    /**
     * Renders a member's JVM descriptor.
     *
     * <p>A method becomes its parameter descriptors between parentheses followed by its return
     * descriptor; anything else is rendered as the descriptor of its own type, which is what a
     * field wants. The parameters are the ones the source declares, and neither the thrown types
     * nor the generic signature is part of a descriptor.
     *
     * @param member the member to render; must not be {@code null}
     * @return the descriptor, subject to the substitutions {@link #typeDescriptorOf(TypeMirror)}
     *         makes
     */
    @Contract(pure = true)
    @NotNull
    static String descriptorOf(@NotNull final Element member) {
        if (member instanceof ExecutableElement method) {
            final StringBuilder out = new StringBuilder("(");
            for (final VariableElement parameter : method.getParameters()) {
                out.append(typeDescriptorOf(parameter.asType()));
            }
            return out.append(')').append(typeDescriptorOf(method.getReturnType())).toString();
        }
        return typeDescriptorOf(member.asType());
    }

    /**
     * Renders one type as a JVM field descriptor.
     *
     * <p>The eight primitive spellings, {@code V} for {@code void}, {@code [} before a component
     * and {@code L…;} around a binary name are exact. Two cases are not, and both matter to
     * anything that resolves a member by the descriptor recorded here.
     *
     * <ul>
     *   <li>A type variable is rendered as its upper bound, which is the erasure only while the
     *       bound is single. A variable declared {@code <T extends CharSequence & Runnable>} has an
     *       intersection type as its upper bound, which no case matches, so it falls to the default
     *       and is rendered {@code Ljava/lang/Object;} where the compiler writes
     *       {@code Ljava/lang/CharSequence;} into the class file.
     *   <li>Every other kind the switch does not name — an unresolved type among them — is
     *       rendered {@code Ljava/lang/Object;} rather than refused.
     * </ul>
     *
     * @param type the type to render; must not be {@code null}
     * @return its descriptor
     */
    @Contract(pure = true)
    @NotNull
    static String typeDescriptorOf(@NotNull final TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case CHAR -> "C";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case ARRAY -> '[' + typeDescriptorOf(((ArrayType) type).getComponentType());
            // A type variable erases to its first bound, which is what the class file holds.
            case TYPEVAR -> typeDescriptorOf(((TypeVariable) type).getUpperBound());
            case DECLARED -> 'L' + binaryNameOf((DeclaredType) type).replace('.', '/') + ';';
            default -> "Ljava/lang/Object;";
        };
    }

    /**
     * Assembles a declared type's binary name from the source nesting.
     *
     * <p>Each enclosing type's simple name is prepended with a {@code $} and the enclosing package,
     * where there is one, with a dot. The name is built rather than asked for, so it follows what
     * the source says a type is nested in; a type in the unnamed package is returned without a
     * prefix.
     *
     * @param type the type to name; must not be {@code null}
     * @return the binary name, with dots still separating the package from the class
     */
    @Contract(pure = true)
    @NotNull
    private static String binaryNameOf(@NotNull final DeclaredType type) {
        final TypeElement element = (TypeElement) type.asElement();
        final StringBuilder name = new StringBuilder(element.getSimpleName());
        Element enclosing = element.getEnclosingElement();
        while (enclosing instanceof TypeElement outer) {
            name.insert(0, outer.getSimpleName() + "$");
            enclosing = outer.getEnclosingElement();
        }
        while (enclosing != null && !(enclosing instanceof javax.lang.model.element.PackageElement)) {
            enclosing = enclosing.getEnclosingElement();
        }
        if (enclosing instanceof javax.lang.model.element.PackageElement pkg
                && !pkg.getQualifiedName().isEmpty()) {
            return pkg.getQualifiedName() + "." + name;
        }
        return name.toString();
    }

    /**
     * Strips an accessor or invoker prefix off a declared name.
     *
     * <p>A prefix counts only when something follows it and that something is an upper-case
     * character, which is what keeps {@code setup} from becoming {@code up} and {@code get} from
     * becoming the empty string. The first matching prefix in the list order wins. The character
     * after the prefix is lower-cased, so {@code getLedger} yields {@code ledger}.
     *
     * <p>A name matching no prefix is returned unchanged, which is how an accessor called something
     * else entirely ends up bound to a field of that same name.
     *
     * @param name     the declared name; must not be {@code null}
     * @param prefixes the prefixes to try, in order; must not be {@code null}
     * @return the inferred member name, or {@code name} when no prefix applies
     */
    @Contract(pure = true)
    @NotNull
    private static String inferred(@NotNull final String name,
                                   @NotNull final List<String> prefixes) {
        for (final String prefix : prefixes) {
            if (name.length() > prefix.length() && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                return Character.toLowerCase(name.charAt(prefix.length()))
                        + name.substring(prefix.length() + 1);
            }
        }
        return name;
    }
}
