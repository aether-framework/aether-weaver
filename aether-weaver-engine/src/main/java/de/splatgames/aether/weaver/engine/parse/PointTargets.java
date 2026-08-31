package de.splatgames.aether.weaver.engine.parse;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.select.MemberKind;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Decides which member grammar an {@code @At}'s {@code target} has to parse as.
 *
 * <p>Public, and separate from the parser, because the IDE plugin resolves and completes the same
 * targets and has to reach the same answer as the build does.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class PointTargets {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private PointTargets() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the grammar a point's target is written in.
     *
     * <p>The comparison is by name rather than by constant, because a point may be a custom
     * identifier that no {@link Point} constant declares. A custom point spelled exactly like a
     * built-in one therefore gets that built-in one's answer.
     *
     * @param point the point's name, as {@code PointSpec} carries it; must not be {@code null}
     * @return {@link MemberKind#FIELD} for {@link Point#FIELD}; {@link MemberKind#METHOD} for
     *         {@link Point#INVOKE}, {@link Point#INVOKE_AFTER} and {@link Point#CONSTANT}; and
     *         {@code null} for every other point, including {@link Point#NEW}, whose target names a
     *         class rather than a member, and the points that take no target at all
     * @throws NullPointerException if {@code point} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static MemberKind selectorKindFor(@NotNull final String point) {
        Objects.requireNonNull(point, "point");
        if (point.equals(Point.FIELD.name())) {
            return MemberKind.FIELD;
        }
        if (point.equals(Point.INVOKE.name())
                || point.equals(Point.INVOKE_AFTER.name())
                || point.equals(Point.CONSTANT.name())) {
            return MemberKind.METHOD;
        }
        return null;
    }
}
