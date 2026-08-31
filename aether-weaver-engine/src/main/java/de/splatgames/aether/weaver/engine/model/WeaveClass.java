package de.splatgames.aether.weaver.engine.model;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.model.GroupSpec;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.Weave;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.constant.ClassDesc;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One {@link Weave} class as the engine understands it: what it targets, what it declares, and
 * where the declaration was found.
 *
 * <p>The model carries declarations and no code. A {@link WeaveMember} names a member's type and
 * flags but never its body, so a stage that has to move a body — structural weaving — reads the
 * weave's class file again through
 * {@link de.splatgames.aether.weaver.engine.merge.WeaveBytes} and reports {@code AW1096} when
 * nothing supplies it.
 *
 * <p>Every collection is copied on construction, so a weave handed to
 * {@link de.splatgames.aether.weaver.engine.Weaver} cannot be changed underneath the plan built
 * from it.
 *
 * @param weaveType the weave class's own type
 * @param targets   the classes it applies to; at least one
 * @param kind      whether the weave dissolves into each target or stays a class of its own
 * @param priority  the ordering key, higher first, where two declarations meet at one place
 * @param require   whether an unresolvable target is a compile-time error; recorded here and read by
 *                  no other production code
 * @param phase     the declared phase
 * @param tags      the labels a deployment switches this weave on or off by
 * @param groups    the match groups it declares, no two of them named alike
 * @param members   the fields and methods it declares, in the order they were parsed
 * @param injectors the injections it declares, in the order they were parsed
 * @param origin    where the declaration was found, for diagnostics
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record WeaveClass(ClassDesc weaveType,
                         @Unmodifiable List<TargetRef> targets,
                         Weave.Kind kind,
                         int priority,
                         Require require,
                         Phase phase,
                         @Unmodifiable Set<String> tags,
                         @Unmodifiable List<GroupSpec> groups,
                         @Unmodifiable List<WeaveMember> members,
                         @Unmodifiable List<InjectorSpec> injectors,
                         Origin origin) {

    /**
     * Copies every collection and checks the two invariants later stages rely on.
     *
     * <p>A weave with no target could never apply, and two groups of one name would make
     * {@link #groupNamed(String)} answer with whichever was declared first and ignore the other.
     *
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code targets} is empty, or if two groups share a name
     */
    public WeaveClass {
        Objects.requireNonNull(weaveType, "weaveType");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(require, "require");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(origin, "origin");
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
        groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        injectors = List.copyOf(Objects.requireNonNull(injectors, "injectors"));
        if (targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "weave " + weaveType.displayName() + " declares no target");
        }
        final long distinct = groups.stream().map(GroupSpec::name).distinct().count();
        if (distinct != groups.size()) {
            throw new IllegalArgumentException(
                    "weave " + weaveType.displayName() + " declares two groups with the same name");
        }
    }

    /**
     * Returns the weave class's binary name, as {@code com.acme.AuditWeave}.
     *
     * <p>Diagnostics about the weave name it with this, and the plan's order key carries it as the
     * tie-breaker after priority, which is part of what makes two builds of the same inputs order
     * their declarations alike.
     *
     * @return the binary name
     */
    @Contract(pure = true)
    @NotNull
    public String binaryName() {
        final String descriptor = this.weaveType.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    /**
     * Reports whether applying this weave changes the target's own shape rather than only the code
     * inside its methods.
     *
     * <p>Four things count: an instance weave that declares any injector; a merged member; an accessor
     * or an invoker, since a method is generated for each; and a shadow declaring {@code mutable},
     * whether it shadows a field or a method. Only a mutable field shadow actually rewrites anything —
     * a mutable method shadow answers {@code true} here without the target changing at all. A plain,
     * non-mutable shadow does not count either way: it resolves a name the target already has and
     * leaves it as it was. The injector count is broader than the handlers that actually move: a
     * handler declared in another class stays there, but this method does not check where each
     * injector's handler lives.
     *
     * @return whether applying the weave would change what the target declares
     */
    @Contract(pure = true)
    public boolean isStructural() {
        if (this.kind == Weave.Kind.INSTANCE && !this.injectors.isEmpty()) {
            return true;
        }
        for (final WeaveMember member : this.members) {
            final boolean structural = switch (member) {
                case WeaveMember.Merged ignored -> true;
                case WeaveMember.Accessor ignored -> true;
                case WeaveMember.Invoker ignored -> true;
                case WeaveMember.Shadowed shadowed -> shadowed.mutable();
            };
            if (structural) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the group this weave declares under the given name.
     *
     * @param name the group name; must not be {@code null}
     * @return the group, or {@code null} when the weave declares none by that name
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    public @Nullable GroupSpec groupNamed(@NotNull final String name) {
        Objects.requireNonNull(name, "name");
        for (final GroupSpec group : this.groups) {
            if (group.name().equals(name)) {
                return group;
            }
        }
        return null;
    }

    /**
     * Reports whether the weave names the given class among its targets.
     *
     * <p>The comparison is on internal names, so the caller may pass the name a class loader or a
     * transformer handed it without converting anything.
     *
     * @param internalName the class's internal name, such as {@code com/acme/Session}; must not be
     *                     {@code null}
     * @return whether that class is one of the targets
     * @throws NullPointerException if {@code internalName} is {@code null}
     */
    @Contract(pure = true)
    public boolean targets(@NotNull final String internalName) {
        Objects.requireNonNull(internalName, "internalName");
        for (final TargetRef target : this.targets) {
            if (target.internalName().equals(internalName)) {
                return true;
            }
        }
        return false;
    }
}
