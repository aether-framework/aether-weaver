package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.CodeElement;
import java.util.List;

/**
 * A method body as an indexable list of class-file elements.
 *
 * <p>This is the form an {@link InjectionPoint} searches and the coordinate system every position
 * in this SPI is expressed in. A {@link Site} names an element by its position in
 * {@link #elements()}, and the {@code index} an {@link Injector.Emitter} is handed counts the same
 * positions in the same order, so a position found while planning is the position the emitter is
 * called at.
 *
 * <h2>What the list contains</h2>
 *
 * <p>Every element of the method's {@code Code} attribute, in order, not only its instructions.
 * Labels, line numbers, local-variable declarations and exception handlers are elements too, so an
 * index into this list is neither a bytecode offset nor an ordinal among instructions. A point that
 * cares about instructions filters for them:
 *
 * <pre>{@code
 * List<CodeElement> elements = code.elements();
 * for (int at = 0; at < elements.size(); at++) {
 *     if (elements.get(at) instanceof InvokeInstruction invoke
 *             && invoke.owner().asInternalName().startsWith("org/slf4j/")) {
 *         sites.add(new Site(at, Site.Kind.AFTER_ELEMENT, invoke));
 *     }
 * }
 * }</pre>
 *
 * <h2>Which body a view shows</h2>
 *
 * <p>A view obtained from {@link MethodView#code()} is the whole body of that method, and is absent
 * for a method that has none — an abstract or a native one. The view an {@link InjectionPoint}
 * receives may be narrower: where the declaration names a slice, the bounds are resolved before the
 * point runs and the point is handed a view of the slice alone. Positions returned from
 * {@link InjectionPoint#find} are therefore relative to the view that was passed in, and the engine
 * translates them back into the whole body before anything is emitted. This is also why an ordinal
 * counts within the slice rather than within the method.
 *
 * <p>A view is a snapshot of the body as it was read. Weaving builds a new class rather than
 * editing this one, so a view never reflects what another injection is about to add.
 *
 * <p>Instances are supplied by the engine.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Site
 * @see InjectionPoint
 */
@ApiStatus.NonExtendable
public interface CodeView {

    /**
     * Returns the body's elements in the order they appear in the class file.
     *
     * @return the elements, never {@code null} and not modifiable; empty for a body with no
     *         elements
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    List<CodeElement> elements();

    /**
     * Returns the number of elements, which is one past the largest usable index.
     *
     * @return the size of {@link #elements()}
     */
    @Contract(pure = true)
    default int size() {
        return elements().size();
    }
}
