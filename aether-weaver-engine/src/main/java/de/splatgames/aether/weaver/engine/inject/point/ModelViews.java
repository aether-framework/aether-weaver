package de.splatgames.aether.weaver.engine.inject.point;

import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.TargetView;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Adapts a parsed class to the read-only views an injection point is given.
 *
 * <p>The views exist so that a point sees a class without seeing the class-file API's mutable
 * machinery, and this is the only implementation of them the engine has. Everything is read out of
 * the model in the constructor, and every list is copied, so a view's own structure never changes
 * and a view never calls back into the model.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Copying the lists does not copy what they hold. The retained elements — {@link CodeElement} and
 * the rest — are the class-file API's own objects, and that API documents them as lazily inflated and
 * states that, because of the laziness, its models may not be thread safe.
 * Nothing here does anything to make the retained elements safe to touch from more than one thread at
 * a time; a caller that hands the same view to several threads is relying on a guarantee this class
 * does not provide.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ModelViews {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private ModelViews() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the view of a parsed class.
     *
     * <p>Reads the whole class eagerly, including a view of every method and a copy of every
     * method's element list, so the cost is paid here rather than in the point.
     *
     * @param model the parsed class; must not be {@code null}
     * @return a view over it
     * @throws NullPointerException if {@code model} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static TargetView of(@NotNull final ClassModel model) {
        return new ModelTarget(Objects.requireNonNull(model, "model"));
    }

    /**
     * A class, read out of a {@link ClassModel} and held as immutable data.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class ModelTarget implements TargetView {

        /** The class's own descriptor. */
        private final ClassDesc type;

        /** The class's access flags. */
        private final Set<AccessFlag> flags;

        /** The superclass, or {@code null} where the class file names {@code Object} or nothing. */
        private final ClassDesc superclass;

        /** The directly declared interfaces, in declaration order. */
        private final List<ClassDesc> interfaces;

        /** A view of every declared method, in declaration order. */
        private final List<MethodView> methods;

        /**
         * Reads everything out of the model.
         *
         * <p>{@code java.lang.Object} is filtered out of the superclass rather than reported: a
         * class that extends nothing and a class that extends {@code Object} are the same class,
         * and an empty answer says so without a caller having to compare.
         *
         * @param model the class to read; must not be {@code null}
         */
        ModelTarget(@NotNull final ClassModel model) {
            this.type = model.thisClass().asSymbol();
            this.flags = Set.copyOf(model.flags().flags());
            this.superclass = model.superclass()
                    .map(entry -> entry.asSymbol())
                    .filter(desc -> !ConstantDescs.CD_Object.equals(desc))
                    .orElse(null);

            final List<ClassDesc> declared = new ArrayList<>(model.interfaces().size());
            model.interfaces().forEach(entry -> declared.add(entry.asSymbol()));
            this.interfaces = List.copyOf(declared);

            final List<MethodView> views = new ArrayList<>(model.methods().size());
            model.methods().forEach(method -> views.add(new ModelMethod(method)));
            this.methods = List.copyOf(views);
        }

        /**
         * Returns the class's descriptor.
         *
         * @return the descriptor read from the model
         */
        @Override
        @NotNull
        public ClassDesc type() {
            return this.type;
        }

        /**
         * Returns the class's binary name.
         *
         * @return {@link #internalName()} with slashes turned into dots
         */
        @Override
        @NotNull
        public String binaryName() {
            return internalName().replace('/', '.');
        }

        /**
         * Returns the class's internal name.
         *
         * <p>Taken by stripping the {@code L} and the {@code ;} off the descriptor, which is
         * correct for a class or interface and for nothing else. Only a class descriptor reaches
         * this view.
         *
         * @return the internal name, with slashes
         */
        @Override
        @NotNull
        public String internalName() {
            final String descriptor = this.type.descriptorString();
            return descriptor.substring(1, descriptor.length() - 1);
        }

        /**
         * Returns the class's access flags.
         *
         * @return the flags read from the model
         */
        @Override
        @Unmodifiable
        @NotNull
        public Set<AccessFlag> flags() {
            return this.flags;
        }

        /**
         * Returns what the class extends, where that is worth knowing.
         *
         * @return the superclass, or empty where the class file names {@code Object} or nothing
         */
        @Override
        @NotNull
        public Optional<ClassDesc> superclass() {
            return Optional.ofNullable(this.superclass);
        }

        /**
         * Returns the interfaces the class declares.
         *
         * @return the directly declared interfaces, in declaration order
         */
        @Override
        @Unmodifiable
        @NotNull
        public List<ClassDesc> interfaces() {
            return this.interfaces;
        }

        /**
         * Returns the methods the class declares.
         *
         * @return every declared method, including the initialisers and anything synthetic, in
         *         declaration order
         */
        @Override
        @Unmodifiable
        @NotNull
        public List<MethodView> methods() {
            return this.methods;
        }

        /**
         * Returns the declared method with that name and descriptor.
         *
         * <p>Both have to match, so this cannot be used to look up an overload by name; that is
         * what {@link #methods()} and a filter are for. Nothing inherited is considered.
         *
         * @param name       the method name; must not be {@code null}
         * @param descriptor the method descriptor; must not be {@code null}
         * @return the method, or empty when the class declares no such method
         * @throws NullPointerException if either argument is {@code null}
         */
        @Override
        @NotNull
        public Optional<MethodView> method(@NotNull final String name,
                                           @NotNull final MethodTypeDesc descriptor) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
            return this.methods.stream()
                    .filter(method -> name.equals(method.name())
                            && descriptor.equals(method.type()))
                    .findFirst();
        }

        /**
         * Returns the class's internal name.
         *
         * @return {@link #internalName()}
         */
        @Override
        @NotNull
        public String toString() {
            return internalName();
        }
    }

    /**
     * A method, read out of a {@link MethodModel} and held as immutable data.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class ModelMethod implements MethodView {

        /** The method's name, including {@code <init>} and {@code <clinit>}. */
        private final String name;

        /** The method's descriptor. */
        private final MethodTypeDesc type;

        /** The method's access flags. */
        private final Set<AccessFlag> flags;

        /** The body, or {@code null} for an abstract or native method. */
        private final CodeView code;

        /**
         * Reads everything out of the model, including a copy of the body's elements.
         *
         * @param model the method to read; must not be {@code null}
         */
        ModelMethod(@NotNull final MethodModel model) {
            this.name = model.methodName().stringValue();
            this.type = model.methodTypeSymbol();
            this.flags = Set.copyOf(model.flags().flags());
            this.code = model.code().map(ModelCode::new).orElse(null);
        }

        /**
         * Returns the method's name.
         *
         * @return the name as the class file spells it
         */
        @Override
        @NotNull
        public String name() {
            return this.name;
        }

        /**
         * Returns the method's descriptor.
         *
         * @return the descriptor read from the model
         */
        @Override
        @NotNull
        public MethodTypeDesc type() {
            return this.type;
        }

        /**
         * Returns the method's access flags.
         *
         * @return the flags read from the model
         */
        @Override
        @Unmodifiable
        @NotNull
        public Set<AccessFlag> flags() {
            return this.flags;
        }

        /**
         * Returns the method's body.
         *
         * @return the body, or empty for a method with no {@code Code} attribute
         */
        @Override
        @NotNull
        public Optional<CodeView> code() {
            return Optional.ofNullable(this.code);
        }

        /**
         * Returns the method as a diagnostic names it.
         *
         * <p>Name and parameter types by display name, with no return type and no owner, so two
         * methods differing only in return type render identically.
         *
         * @return the rendering, for example {@code charge(BigDecimal, int)}
         */
        @Override
        @NotNull
        public String describe() {
            final StringBuilder sb = new StringBuilder(this.name).append('(');
            final List<ClassDesc> parameters = this.type.parameterList();
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(parameters.get(i).displayName());
            }
            return sb.append(')').toString();
        }

        /**
         * Returns the method as a diagnostic names it.
         *
         * @return {@link #describe()}
         */
        @Override
        @NotNull
        public String toString() {
            return describe();
        }
    }

    /**
     * A method body, held as the fixed element list every index in this package refers to.
     *
     * <p>Positions in this package are indices into that list, so the list has to be the same list
     * for every question asked about one body. Copying it once here is what makes that true.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class ModelCode implements CodeView {

        /** The body's elements, in body order. */
        private final List<CodeElement> elements;

        /**
         * Copies the body's elements.
         *
         * @param model the body to read; must not be {@code null}
         */
        ModelCode(@NotNull final CodeModel model) {
            this.elements = List.copyOf(model.elementList());
        }

        /**
         * Returns the body's elements.
         *
         * <p>Instructions and pseudo-elements alike — labels, line numbers and debug entries are in
         * here too, so an index is not an instruction count.
         *
         * @return the elements, in body order
         */
        @Override
        @Unmodifiable
        @NotNull
        public List<CodeElement> elements() {
            return this.elements;
        }

        /**
         * Returns how many elements the body has.
         *
         * @return a description naming the element count, without listing them
         */
        @Override
        @NotNull
        public String toString() {
            return "CodeView[" + this.elements.size() + " elements]";
        }
    }
}
