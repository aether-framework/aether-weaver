package de.splatgames.aether.weaver.engine.internal.transform;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldBuilder;
import java.lang.classfile.FieldElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Interfaces;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.Superclass;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.attribute.PermittedSubclassesAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Rewrites every type reference in a class according to a mapping.
 *
 * <p>Every one, which is the difficult part. A class file mentions a type in a dozen places that
 * have nothing to do with each other: the superclass and interfaces, field and method descriptors,
 * the owner of an instruction, the type argument of a generic signature, the value of an annotation
 * element, a bootstrap method's arguments, the nest host, the enclosing method, a record component,
 * a permitted subclass, and the debug entry for a local. Missing one leaves the old name in the
 * constant pool of a class that no longer has anything of that name to find.
 *
 * <p>The check for that is a text search rather than a structural one: {@code ClassRemapperTest}
 * asserts that the internal name of the source type appears nowhere in the rewritten bytes at all,
 * including in a pool entry nothing reaches. It also runs an identity mapping over every class in
 * {@code java.base} and requires byte-for-byte equality, which {@link #remap} delivers by not
 * rewriting at all when {@link #affects} says nothing would change.
 *
 * <h2>Thread safety</h2>
 *
 * <p>As safe as the mapping function. The remapper itself holds nothing but that function, and the
 * {@link Map} form copies the map on the way in.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ClassRemapper {

    /** The mapping, total: it answers for every type, with the type itself where nothing changes. */
    private final UnaryOperator<ClassDesc> mapping;

    /**
     * Stores the mapping.
     *
     * @param mapping the mapping to apply; must not be {@code null}
     * @throws NullPointerException if {@code mapping} is {@code null}
     */
    private ClassRemapper(@NotNull final UnaryOperator<ClassDesc> mapping) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
    }

    /**
     * Returns a remapper driven by a lookup table.
     *
     * <p>A type the map does not mention is left alone. The map is copied, so a later change to the
     * caller's map does not change what this remapper does.
     *
     * @param mapping the types to replace, keyed by the type being replaced; must not be
     *                {@code null} and must contain no {@code null}
     * @return a remapper applying that table
     * @throws NullPointerException if {@code mapping} is {@code null} or holds a {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static ClassRemapper of(@NotNull final Map<ClassDesc, ClassDesc> mapping) {
        final Map<ClassDesc, ClassDesc> copy = Map.copyOf(mapping);
        return new ClassRemapper(type -> copy.getOrDefault(type, type));
    }

    /**
     * Returns a remapper driven by a function.
     *
     * <p>The function is asked about class and interface types only — primitives and arrays are
     * decided by {@link #map(ClassDesc)} before it is called — and must answer for every type it is
     * asked about, returning the argument where nothing changes. Returning {@code null} is a
     * programming error and is reported as one.
     *
     * @param mapping the mapping to apply; must not be {@code null}
     * @return a remapper applying that function
     * @throws NullPointerException if {@code mapping} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static ClassRemapper of(@NotNull final UnaryOperator<ClassDesc> mapping) {
        return new ClassRemapper(mapping);
    }

    /**
     * Returns the type a given type becomes.
     *
     * <p>A primitive is never mapped and an array is mapped through its component type, however
     * deeply nested, so a mapping from {@code Foo} to {@code Bar} turns {@code Foo[][]} into
     * {@code Bar[][]} without being asked about either array type. Where the component does not
     * change, the original descriptor is returned rather than a rebuilt one.
     *
     * @param type the type to map; must not be {@code null}
     * @return the mapped type, or {@code type} itself where the mapping changes nothing
     * @throws NullPointerException if {@code type} is {@code null}, or if the mapping returns
     *                              {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public ClassDesc map(@NotNull final ClassDesc type) {
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive()) {
            return type;
        }
        if (type.isArray()) {
            final ClassDesc component = map(type.componentType());
            return component.equals(type.componentType()) ? type : component.arrayType();
        }
        return Objects.requireNonNull(this.mapping.apply(type), "mapping returned null for " + type);
    }

    /**
     * Returns the method type with its return type and every parameter mapped.
     *
     * @param type the method type to map; must not be {@code null}
     * @return the mapped method type, equal to the original where the mapping changes nothing
     * @throws NullPointerException if {@code type} is {@code null}, or if the mapping returns
     *                              {@code null} for one of the types
     */
    @Contract(pure = true)
    @NotNull
    public MethodTypeDesc map(@NotNull final MethodTypeDesc type) {
        Objects.requireNonNull(type, "type");
        final ClassDesc returnType = map(type.returnType());
        final ClassDesc[] parameters = type.parameterList().stream()
                .map(this::map)
                .toArray(ClassDesc[]::new);
        return MethodTypeDesc.of(returnType, parameters);
    }

    /**
     * Returns this remapper as a class transform.
     *
     * <p>The transform rebuilds the class it is applied to, whether or not the mapping changes
     * anything in it; {@link #remap} is the entry point that avoids the rebuild.
     *
     * @return a transform applying this mapping to a whole class
     */
    @NotNull
    public ClassTransform asClassTransform() {
        return this::transformClassElement;
    }

    /**
     * Reports whether this mapping would change any type the class names.
     *
     * <p>Asked of the constant pool rather than of the structure, because a pool entry is what a
     * rewrite has to catch and the structure is only how entries are reached. Every
     * {@code CONSTANT_Class} entry is mapped and compared with itself.
     *
     * <p>An index that holds no entry is skipped rather than treated as an answer. A {@code long}
     * or a {@code double} occupies two indices, and asking for the second throws
     * {@code ConstantPoolException: Unusable CP index} — measured on Temurin 25 — which is what the
     * empty catch is for.
     *
     * @param model the class to examine; must not be {@code null}
     * @return {@code true} when at least one class entry maps to something else
     * @throws NullPointerException if {@code model} is {@code null}
     */
    @Contract(pure = true)
    public boolean affects(@NotNull final java.lang.classfile.ClassModel model) {
        Objects.requireNonNull(model, "model");
        final var pool = model.constantPool();
        for (int i = 1; i < pool.size(); i++) {
            final java.lang.classfile.constantpool.PoolEntry entry;
            try {
                entry = pool.entryByIndex(i);
            } catch (@NotNull final RuntimeException e) {
                continue;   // long and double occupy two indices; the second is not an entry
            }
            if (entry instanceof java.lang.classfile.constantpool.ClassEntry classEntry) {
                final ClassDesc symbol = classEntry.asSymbol();
                if (!map(symbol).equals(symbol)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the class with every reference rewritten, or the original bytes when nothing changes.
     *
     * <p>Three things happen here that {@link #asClassTransform()} on its own does not do.
     *
     * <ul>
     *   <li>A class the mapping does not touch is returned unchanged, byte for byte. A rebuild
     *       would preserve every member and still differ, because the constant pool is re-interned.
     *   <li>The rewrite runs with a fresh constant pool. Sharing the original's pool would carry
     *       every entry across, including the entries for the names being replaced, and the text
     *       search {@code ClassRemapperTest} performs would find them.
     *   <li>A mapping that covers the class's own name renames the class as well, which is what
     *       folding a weave into its target needs.
     * </ul>
     *
     * @param classFile the context to transform through; must not be {@code null}
     * @param model     the parsed form of {@code original}; must not be {@code null}
     * @param original  the class file bytes; must not be {@code null}
     * @return the rewritten bytes, or {@code original} itself when the mapping touches nothing
     * @throws NullPointerException if any argument is {@code null}
     */
    @NotNull
    public byte[] remap(@NotNull final java.lang.classfile.ClassFile classFile,
                        @NotNull final java.lang.classfile.ClassModel model,
                        @NotNull final byte[] original) {
        Objects.requireNonNull(classFile, "classFile");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(original, "original");
        if (!affects(model)) {
            return original;
        }
        final ClassDesc self = model.thisClass().asSymbol();
        final ClassDesc renamed = map(self);
        final java.lang.classfile.ClassFile freshPool = classFile
                .withOptions(java.lang.classfile.ClassFile.ConstantPoolSharingOption.NEW_POOL);
        // A class's own name is a reference like any other. If the mapping covers it, the class
        // is renamed as well — which is exactly what folding a weave into its target requires.
        return renamed.equals(self)
                ? freshPool.transformClass(model, asClassTransform())
                : freshPool.transformClass(model, renamed, asClassTransform());
    }

    /**
     * Returns the constant-pool option a caller driving the transform itself has to set.
     *
     * <p>{@link #remap} sets it on the context it is given; a caller that applies
     * {@link #asClassTransform()} or {@link #asCodeTransform()} to a context of its own does not
     * get it for free and will otherwise leave the replaced names in the pool.
     *
     * @return the option requiring a fresh constant pool
     */
    @NotNull
    public static java.lang.classfile.ClassFile.Option requiredPoolOption() {
        return java.lang.classfile.ClassFile.ConstantPoolSharingOption.NEW_POOL;
    }

    /**
     * Returns this remapper as a code transform, for a single body rather than a whole class.
     *
     * <p>Rewrites the references inside instructions and the two debug pseudo-elements. A method's
     * own descriptor, its {@code Exceptions} and its signature sit outside the body and are not
     * reached this way — {@link #asClassTransform()} is what covers those.
     *
     * @return a transform applying this mapping to a body
     */
    @NotNull
    public CodeTransform asCodeTransform() {
        return this::transformCodeElement;
    }

    // ---------------------------------------------------------------------------------------
    // class level
    // ---------------------------------------------------------------------------------------

    /**
     * Rewrites one class-level element.
     *
     * <p>A field or a method is rebuilt rather than forwarded, because its descriptor is part of
     * the {@code withField} and {@code withMethod} call and cannot be changed afterwards; the
     * elements inside it are then handed to the field and method cases.
     *
     * <p>The {@code default} forwards, so an element kind with no case here survives unchanged. For
     * an attribute that mentions no type that is correct; for one that does, it is the silent
     * failure this switch exists to avoid, which is why the list is as long as it is.
     *
     * @param cb      the class being built; must not be {@code null}
     * @param element the element to rewrite; must not be {@code null}
     */
    private void transformClassElement(@NotNull final ClassBuilder cb, @NotNull final ClassElement element) {
        switch (element) {
            case Superclass superclass -> cb.withSuperclass(map(superclass.superclassEntry().asSymbol()));

            case Interfaces interfaces -> cb.withInterfaceSymbols(
                    interfaces.interfaces().stream().map(i -> map(i.asSymbol())).toList());

            case FieldModel field -> cb.withField(
                    field.fieldName().stringValue(),
                    map(ClassDesc.ofDescriptor(field.fieldType().stringValue())),
                    fb -> field.forEach(fe -> transformFieldElement(fb, fe)));

            case MethodModel method -> cb.withMethod(
                    method.methodName().stringValue(),
                    map(method.methodTypeSymbol()),
                    method.flags().flagsMask(),
                    mb -> method.forEach(me -> transformMethodElement(mb, me)));

            case SignatureAttribute signature -> cb.with(SignatureAttribute.of(
                    mapClassSignature(signature.asClassSignature())));

            case RuntimeVisibleAnnotationsAttribute annotations -> cb.with(
                    RuntimeVisibleAnnotationsAttribute.of(mapAnnotations(annotations.annotations())));

            case RuntimeInvisibleAnnotationsAttribute annotations -> cb.with(
                    RuntimeInvisibleAnnotationsAttribute.of(mapAnnotations(annotations.annotations())));

            case NestHostAttribute nestHost ->
                    cb.with(NestHostAttribute.of(map(nestHost.nestHost().asSymbol())));

            case NestMembersAttribute nestMembers -> cb.with(NestMembersAttribute.ofSymbols(
                    nestMembers.nestMembers().stream().map(m -> map(m.asSymbol())).toList()));

            case java.lang.classfile.attribute.InnerClassesAttribute innerClasses ->
                    cb.with(java.lang.classfile.attribute.InnerClassesAttribute.of(
                            innerClasses.classes().stream().map(this::mapInnerClass).toList()));

            case java.lang.classfile.attribute.EnclosingMethodAttribute enclosing ->
                    cb.with(java.lang.classfile.attribute.EnclosingMethodAttribute.of(
                            map(enclosing.enclosingClass().asSymbol()),
                            enclosing.enclosingMethodName().map(n -> n.stringValue()),
                            enclosing.enclosingMethodTypeSymbol().map(this::map)));

            case java.lang.classfile.attribute.RecordAttribute record ->
                    cb.with(java.lang.classfile.attribute.RecordAttribute.of(
                            record.components().stream().map(this::mapRecordComponent).toList()));

            case PermittedSubclassesAttribute permitted -> cb.with(
                    PermittedSubclassesAttribute.ofSymbols(permitted.permittedSubclasses().stream()
                            .map(p -> map(p.asSymbol())).toList()));

            default -> cb.with(element);
        }
    }

    /**
     * Returns one {@code InnerClasses} row with its inner and outer class mapped.
     *
     * <p>The simple name is carried over unchanged. It is a name, not a reference, and a mapping
     * that renames the class does not rename what the source called it.
     *
     * @param info the row to rewrite; must not be {@code null}
     * @return the row with mapped types
     */
    private java.lang.classfile.attribute.InnerClassInfo mapInnerClass(
            @NotNull final java.lang.classfile.attribute.InnerClassInfo info) {
        return java.lang.classfile.attribute.InnerClassInfo.of(
                map(info.innerClass().asSymbol()),
                info.outerClass().map(o -> map(o.asSymbol())),
                info.innerName().map(n -> n.stringValue()),
                info.flagsMask());
    }

    /**
     * Returns one record component with its descriptor and generic signature mapped.
     *
     * <p>Every attribute other than the signature is carried over as it is. The list is filled in
     * by assigning one element at a time, which makes each addition a widening reference conversion
     * to {@code Attribute<?>} rather than an inference over the whole collection.
     *
     * @param component the component to rewrite; must not be {@code null}
     * @return the component with mapped types
     */
    private java.lang.classfile.attribute.RecordComponentInfo mapRecordComponent(
            @NotNull final java.lang.classfile.attribute.RecordComponentInfo component) {
        // Built element by element rather than with stream().map(...).toList(), and not for
        // style. Attribute is declared Attribute<A extends Attribute<A>>, so the conditional's
        // inferred type is Attribute<? extends Attribute<?>> — and List is invariant, so
        // List<Attribute<? extends Attribute<?>>> does not assign to List<Attribute<?>>. javac
        // accepts it anyway; ecj does not, which meant this file built cleanly from Maven and showed
        // a red error to every contributor using IntelliJ's own compiler. Assigning each element
        // separately is a widening reference conversion, which both compilers agree about.
        final List<java.lang.classfile.Attribute<?>> attributes =
                new java.util.ArrayList<>(component.attributes().size());
        for (final java.lang.classfile.Attribute<?> attribute : component.attributes()) {
            if (attribute instanceof SignatureAttribute signature) {
                attributes.add(SignatureAttribute.of(mapSignature(signature.asTypeSignature())));
            } else {
                attributes.add(attribute);
            }
        }
        return java.lang.classfile.attribute.RecordComponentInfo.of(
                component.name().stringValue(),
                map(component.descriptorSymbol()),
                attributes);
    }

    /**
     * Rewrites one field-level element.
     *
     * <p>The field's descriptor is not among them: it was fixed when the field was declared in
     * {@code transformClassElement}.
     *
     * @param fb      the field being built; must not be {@code null}
     * @param element the element to rewrite; must not be {@code null}
     */
    private void transformFieldElement(@NotNull final FieldBuilder fb, @NotNull final FieldElement element) {
        switch (element) {
            case SignatureAttribute signature -> fb.with(SignatureAttribute.of(
                    mapSignature(signature.asTypeSignature())));
            case RuntimeVisibleAnnotationsAttribute a -> fb.with(
                    RuntimeVisibleAnnotationsAttribute.of(mapAnnotations(a.annotations())));
            case RuntimeInvisibleAnnotationsAttribute a -> fb.with(
                    RuntimeInvisibleAnnotationsAttribute.of(mapAnnotations(a.annotations())));
            default -> fb.with(element);
        }
    }

    /**
     * Rewrites one method-level element.
     *
     * <p>The body is transformed rather than replaced, so labels stay in their own context and no
     * relabelling is needed here. The method's descriptor was fixed when the method was declared in
     * {@code transformClassElement}.
     *
     * @param mb      the method being built; must not be {@code null}
     * @param element the element to rewrite; must not be {@code null}
     */
    private void transformMethodElement(@NotNull final MethodBuilder mb, @NotNull final MethodElement element) {
        switch (element) {
            case java.lang.classfile.CodeModel code ->
                    mb.transformCode(code, this::transformCodeElement);

            case ExceptionsAttribute exceptions -> mb.with(ExceptionsAttribute.ofSymbols(
                    exceptions.exceptions().stream().map(e -> map(e.asSymbol())).toList()));

            case SignatureAttribute signature -> mb.with(SignatureAttribute.of(
                    mapMethodSignature(signature.asMethodSignature())));

            case RuntimeVisibleAnnotationsAttribute a -> mb.with(
                    RuntimeVisibleAnnotationsAttribute.of(mapAnnotations(a.annotations())));

            case RuntimeInvisibleAnnotationsAttribute a -> mb.with(
                    RuntimeInvisibleAnnotationsAttribute.of(mapAnnotations(a.annotations())));

            default -> mb.with(element);
        }
    }

    // ---------------------------------------------------------------------------------------
    // code level
    // ---------------------------------------------------------------------------------------

    /**
     * Rewrites one element of a body.
     *
     * <p>Covers the instructions that name a type, both as an owner and as an operand, the constant
     * loads, the {@code invokedynamic} call site with everything reachable from it, and the two
     * debug pseudo-elements. Labels are forwarded untouched: the elements are rebuilt around the
     * labels they already carry, which is correct while the body stays in its own context.
     *
     * @param cb      the body being built; must not be {@code null}
     * @param element the element to rewrite; must not be {@code null}
     */
    private void transformCodeElement(@NotNull final CodeBuilder cb, @NotNull final CodeElement element) {
        switch (element) {
            case InvokeInstruction invoke -> cb.invoke(
                    invoke.opcode(),
                    map(invoke.owner().asSymbol()),
                    invoke.name().stringValue(),
                    map(invoke.typeSymbol()),
                    invoke.isInterface());

            case FieldInstruction field -> cb.fieldAccess(
                    field.opcode(),
                    map(field.owner().asSymbol()),
                    field.name().stringValue(),
                    map(field.typeSymbol()));

            case TypeCheckInstruction check ->
                    cb.with(TypeCheckInstruction.of(check.opcode(), map(check.type().asSymbol())));

            case NewObjectInstruction newObject -> cb.new_(map(newObject.className().asSymbol()));

            case NewReferenceArrayInstruction newArray ->
                    cb.anewarray(map(newArray.componentType().asSymbol()));

            case NewMultiArrayInstruction newArray -> cb.multianewarray(
                    map(newArray.arrayType().asSymbol()), newArray.dimensions());

            case InvokeDynamicInstruction indy -> cb.invokedynamic(mapCallSite(indy));

            case java.lang.classfile.instruction.ConstantInstruction.LoadConstantInstruction load ->
                    cb.loadConstant(mapConstant(load.constantValue()));

            // Debug information records the variable's type as a descriptor. Leaving it
            // unmapped keeps the old name alive in the pool and makes a debugger show a type
            // that no longer exists.
            case java.lang.classfile.instruction.LocalVariable local -> cb.localVariable(
                    local.slot(), local.name().stringValue(),
                    map(ClassDesc.ofDescriptor(local.type().stringValue())),
                    local.startScope(), local.endScope());

            // A generic local carries its own signature in addition to that descriptor.
            case java.lang.classfile.instruction.LocalVariableType localType -> cb.localVariableType(
                    localType.slot(), localType.name().stringValue(),
                    mapSignature(Signature.parseFrom(localType.signature().stringValue())),
                    localType.startScope(), localType.endScope());

            default -> cb.with(element);
        }
    }

    /**
     * Returns the call site of an {@code invokedynamic} with everything in it mapped.
     *
     * <p>Three of the four parts carry references and are mapped: the bootstrap method handle, the
     * invocation type, and each bootstrap argument, which for a lambda is where the implementation
     * method travels. The invocation name is a name and is passed through unmapped.
     *
     * @param indy the instruction to rewrite; must not be {@code null}
     * @return the mapped call site descriptor
     */
    private DynamicCallSiteDesc mapCallSite(@NotNull final InvokeDynamicInstruction indy) {
        final DynamicCallSiteDesc original = indy.invokedynamic().asSymbol();
        final ConstantDesc[] arguments = original.bootstrapArgs();
        final ConstantDesc[] mapped = new ConstantDesc[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            mapped[i] = mapConstant(arguments[i]);
        }
        return DynamicCallSiteDesc.of(
                (DirectMethodHandleDesc) mapMethodHandle(original.bootstrapMethod()),
                original.invocationName(),
                map(original.invocationType()),
                mapped);
    }

    /**
     * Returns a constant with any type reference inside it mapped.
     *
     * <p>Four of the constant forms carry a reference and a dynamic constant carries them
     * recursively. A string, a number and anything else is returned as it is.
     *
     * @param constant the constant to rewrite; must not be {@code null}
     * @return the mapped constant, equal to the original where nothing in it changes
     */
    private ConstantDesc mapConstant(@NotNull final ConstantDesc constant) {
        return switch (constant) {
            case ClassDesc type -> map(type);
            case MethodTypeDesc type -> map(type);
            case MethodHandleDesc handle -> mapMethodHandle(handle);
            case DynamicConstantDesc<?> dynamic -> mapDynamicConstant(dynamic);
            default -> constant;
        };
    }

    /**
     * Returns a method handle descriptor with its owner and its type mapped.
     *
     * <p>A handle that is not direct is returned unchanged, having no owner to map.
     *
     * <p>The two factories are not interchangeable: a field handle's lookup descriptor is a field
     * descriptor and a method handle's is a method descriptor, so the kind decides which of the two
     * the string is parsed as and which factory rebuilds it. {@code DirectMethodHandleDesc.Kind}
     * offers no predicate for this, which is why {@link #isFieldKind} names the four kinds.
     *
     * @param handle the descriptor to rewrite; must not be {@code null}
     * @return the mapped descriptor
     */
    private MethodHandleDesc mapMethodHandle(@NotNull final MethodHandleDesc handle) {
        if (!(handle instanceof DirectMethodHandleDesc direct)) {
            return handle;
        }
        final ClassDesc owner = map(direct.owner());
        // DirectMethodHandleDesc.Kind has no isField(): the four field kinds must be named.
        // A field handle's lookupDescriptor is a field descriptor, a method handle's is a method
        // descriptor, and passing one to the other's factory fails at run time rather than here.
        return isFieldKind(direct.kind())
                ? MethodHandleDesc.ofField(direct.kind(), owner, direct.methodName(),
                        map(ClassDesc.ofDescriptor(direct.lookupDescriptor())))
                : MethodHandleDesc.of(direct.kind(), owner, direct.methodName(),
                        map(MethodTypeDesc.ofDescriptor(direct.lookupDescriptor()))
                                .descriptorString());
    }

    /**
     * Reports whether a handle kind accesses a field rather than invoking a method.
     *
     * @param kind the handle kind; must not be {@code null}
     * @return {@code true} for the getter and setter kinds, static or not
     */
    private static boolean isFieldKind(@NotNull final DirectMethodHandleDesc.Kind kind) {
        return switch (kind) {
            case GETTER, SETTER, STATIC_GETTER, STATIC_SETTER -> true;
            default -> false;
        };
    }

    /**
     * Returns a dynamic constant with its bootstrap method, type and arguments mapped.
     *
     * <p>The arguments are mapped through {@link #mapConstant}, so a dynamic constant nested inside
     * another is reached.
     *
     * @param dynamic the constant to rewrite; must not be {@code null}
     * @return the mapped constant
     */
    private ConstantDesc mapDynamicConstant(@NotNull final DynamicConstantDesc<?> dynamic) {
        final ConstantDesc[] arguments = dynamic.bootstrapArgs();
        final ConstantDesc[] mapped = new ConstantDesc[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            mapped[i] = mapConstant(arguments[i]);
        }
        return DynamicConstantDesc.ofNamed(
                (DirectMethodHandleDesc) mapMethodHandle(dynamic.bootstrapMethod()),
                dynamic.constantName(),
                map(dynamic.constantType()),
                mapped);
    }

    // ---------------------------------------------------------------------------------------
    // signatures and annotations
    // ---------------------------------------------------------------------------------------

    /**
     * Returns a class signature with its type parameters, superclass and interfaces mapped.
     *
     * @param signature the signature to rewrite; must not be {@code null}
     * @return the mapped signature
     */
    private ClassSignature mapClassSignature(@NotNull final ClassSignature signature) {
        return ClassSignature.of(
                signature.typeParameters().stream().map(this::mapTypeParam).toList(),
                (Signature.ClassTypeSig) mapSignature(signature.superclassSignature()),
                signature.superinterfaceSignatures().stream()
                        .map(s -> (Signature.ClassTypeSig) mapSignature(s))
                        .toArray(Signature.ClassTypeSig[]::new));
    }

    /**
     * Returns a method signature with its type parameters, throws clause, result and arguments
     * mapped.
     *
     * @param signature the signature to rewrite; must not be {@code null}
     * @return the mapped signature
     */
    private MethodSignature mapMethodSignature(@NotNull final MethodSignature signature) {
        return MethodSignature.of(
                signature.typeParameters().stream().map(this::mapTypeParam).toList(),
                signature.throwableSignatures().stream()
                        .map(s -> (Signature.ThrowableSig) mapSignature((Signature) s)).toList(),
                mapSignature(signature.result()),
                signature.arguments().stream().map(this::mapSignature).toArray(Signature[]::new));
    }

    /**
     * Returns a type parameter with its bounds mapped.
     *
     * <p>The identifier is the parameter's own name and is not a reference, so it is carried over.
     *
     * @param parameter the type parameter to rewrite; must not be {@code null}
     * @return the type parameter with mapped bounds
     */
    private Signature.TypeParam mapTypeParam(@NotNull final Signature.TypeParam parameter) {
        return Signature.TypeParam.of(
                parameter.identifier(),
                parameter.classBound().map(b -> (Signature.RefTypeSig) mapSignature(b)),
                parameter.interfaceBounds().stream()
                        .map(b -> (Signature.RefTypeSig) mapSignature(b))
                        .toArray(Signature.RefTypeSig[]::new));
    }

    /**
     * Returns a signature with every type in it mapped.
     *
     * <p>Recursive through the three places that contain another signature: an outer type via
     * {@link #mapClassTypeSig}, a bounded type argument via {@link #mapTypeArg}, and an array
     * component. A type variable and a primitive contain no reference and are returned unchanged,
     * which is what the {@code default} covers.
     *
     * @param signature the signature to rewrite; must not be {@code null}
     * @return the mapped signature
     */
    private Signature mapSignature(@NotNull final Signature signature) {
        return switch (signature) {
            case Signature.ClassTypeSig classType -> mapClassTypeSig(classType);
            case Signature.ArrayTypeSig array ->
                    Signature.ArrayTypeSig.of(mapSignature(array.componentSignature()));
            default -> signature;
        };
    }

    /**
     * Returns a class type signature with its outer type, its own type and its type arguments
     * mapped.
     *
     * <p>A member class is written two ways and the two are not interchangeable. Where no enclosing
     * type is parameterized it is written flat, as {@code Ljava/util/Map$Entry;}, and
     * {@link Signature.ClassTypeSig#className()} is the whole slash-separated name. Where one is, it
     * is written as a chain, as {@code Ljava/util/EnumMap<TK;TV;>.EntrySet;}, and {@code className()}
     * is the simple name alone — the rest of it is in the outer signature, which is the only place
     * the enclosing type's arguments can be stated.
     *
     * <p>{@link Signature.ClassTypeSig#classDesc()} answers with the whole name either way, so
     * rebuilding a chained signature from it puts the whole name where the simple name belongs and
     * produces {@code Ljava/util/EnumMap<TK;TV;>.java/util/EnumMap$EntrySet;}. This takes the whole
     * name back apart against the mapped outer type to recover the simple name.
     *
     * <p>Where the mapping moves the member out of its enclosing type, no simple name relates the
     * two and the chain cannot be kept. The flat form is emitted instead, which costs the enclosing
     * type's arguments; a name that no longer nests has nowhere to state them.
     *
     * @param classType the class type signature to rewrite; must not be {@code null}
     * @return the mapped class type signature
     */
    @NotNull
    private Signature.ClassTypeSig mapClassTypeSig(@NotNull final Signature.ClassTypeSig classType) {
        // Type arguments carry references of their own: List<Foo> mentions Foo only here. Passing
        // them through leaves a stale name in the signature.
        final Signature.TypeArg[] typeArgs = classType.typeArgs().stream().map(this::mapTypeArg)
                .toArray(Signature.TypeArg[]::new);
        final ClassDesc mapped = map(classType.classDesc());
        final Optional<Signature.ClassTypeSig> outer = classType.outerType();
        if (outer.isEmpty()) {
            return Signature.ClassTypeSig.of(mapped, typeArgs);
        }
        final Signature.ClassTypeSig mappedOuter = mapClassTypeSig(outer.get());
        final String simpleName = simpleNameWithin(mappedOuter.classDesc(), mapped);
        return simpleName == null
                ? Signature.ClassTypeSig.of(mapped, typeArgs)
                : Signature.ClassTypeSig.of(mappedOuter, simpleName, typeArgs);
    }

    /**
     * Returns the simple name a member type has within an enclosing type, or {@code null} where the
     * one is not a member of the other.
     *
     * <p>Compared as descriptor strings rather than through {@link ClassDesc#displayName()}, which
     * reports the whole {@code Outer$Inner} for a nested type and so cannot tell the two apart. A
     * name is a member when it is the enclosing name, a {@code $}, and at least one more character;
     * everything after that {@code $} is the simple name, further {@code $} included, so a type
     * nested three deep in a chain that only names two of them still resolves.
     *
     * @param enclosing the enclosing type; must not be {@code null}
     * @param member    the candidate member type; must not be {@code null}
     * @return the simple name, or {@code null} where {@code member} is not a member of
     *         {@code enclosing}
     */
    @Contract(pure = true)
    private static String simpleNameWithin(@NotNull final ClassDesc enclosing,
                                           @NotNull final ClassDesc member) {
        final String enclosingDescriptor = enclosing.descriptorString();
        final String memberDescriptor = member.descriptorString();
        final String prefix = enclosingDescriptor.substring(0, enclosingDescriptor.length() - 1) + '$';
        return memberDescriptor.startsWith(prefix) && memberDescriptor.length() > prefix.length() + 1
                ? memberDescriptor.substring(prefix.length(), memberDescriptor.length() - 1)
                : null;
    }

    /**
     * Returns a type argument with its bound mapped.
     *
     * <p>An unbounded wildcard names no type and is returned as it is.
     *
     * @param argument the type argument to rewrite; must not be {@code null}
     * @return the mapped type argument
     */
    private Signature.TypeArg mapTypeArg(@NotNull final Signature.TypeArg argument) {
        return argument instanceof Signature.TypeArg.Bounded bounded
                ? Signature.TypeArg.bounded(bounded.wildcardIndicator(),
                        (Signature.RefTypeSig) mapSignature(bounded.boundType()))
                : argument;
    }

    /**
     * Returns the annotations with every type in them mapped.
     *
     * @param annotations the annotations to rewrite; must not be {@code null}
     * @return the mapped annotations, in the same order
     */
    private List<Annotation> mapAnnotations(@NotNull final List<Annotation> annotations) {
        return annotations.stream().map(this::mapAnnotation).toList();
    }

    /**
     * Returns one annotation with its own type and every element value mapped.
     *
     * <p>Element names are carried over; they name a method of the annotation type rather than a
     * type.
     *
     * @param annotation the annotation to rewrite; must not be {@code null}
     * @return the mapped annotation
     */
    private Annotation mapAnnotation(@NotNull final Annotation annotation) {
        return Annotation.of(
                map(annotation.classSymbol()),
                annotation.elements().stream()
                        .map(e -> AnnotationElement.of(e.name().stringValue(),
                                mapAnnotationValue(e.value())))
                        .toList());
    }

    /**
     * Returns one annotation element value with any type in it mapped.
     *
     * <p>Four of the value forms name a type: a class literal, a nested annotation, an array of
     * values, and an enum constant, whose declaring type is a reference while its constant name is
     * not. Every primitive and string value is returned as it is.
     *
     * @param value the value to rewrite; must not be {@code null}
     * @return the mapped value
     */
    private AnnotationValue mapAnnotationValue(@NotNull final AnnotationValue value) {
        return switch (value) {
            case AnnotationValue.OfClass ofClass ->
                    AnnotationValue.ofClass(map(ofClass.classSymbol()));
            case AnnotationValue.OfAnnotation ofAnnotation ->
                    AnnotationValue.ofAnnotation(mapAnnotation(ofAnnotation.annotation()));
            case AnnotationValue.OfArray ofArray -> AnnotationValue.ofArray(
                    ofArray.values().stream().map(this::mapAnnotationValue).toList());
            case AnnotationValue.OfEnum ofEnum -> AnnotationValue.ofEnum(
                    map(ofEnum.classSymbol()), ofEnum.constantName().stringValue());
            default -> value;
        };
    }

    /**
     * Returns the class name alone.
     *
     * <p>The mapping is not printed; it may be an arbitrary function, which has no rendering worth
     * showing.
     *
     * @return {@code "ClassRemapper"}
     */
    @Override
    public String toString() {
        return "ClassRemapper";
    }
}
