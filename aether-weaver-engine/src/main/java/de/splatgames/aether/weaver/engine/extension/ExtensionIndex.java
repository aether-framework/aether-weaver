package de.splatgames.aether.weaver.engine.extension;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.api.spi.Reporter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers, for a call site, which contributed extension it should reach.
 *
 * <p>Two things make this more than a map. A call may name a subtype of the type the extension was
 * declared on, so the answer needs the receiver's hierarchy; and a real member found on the way up
 * beats an extension, because {@code javac} resolved the call to that member and rewriting it would
 * redirect a call that was already correct.
 *
 * <p>Reading the hierarchy means reading class files, which is expensive and happens while classes
 * are being loaded. Both results are therefore cached: {@code resolved} keyed by the query, and
 * {@code types} by internal name. Neither cache holds {@code null}, since a {@code ConcurrentHashMap}
 * cannot store one — an unresolved query is an empty {@link Optional} and an unreadable class is the
 * {@code Type.UNKNOWN} sentinel.
 *
 * <p>Where a call has to walk the hierarchy and part of it cannot be read, the answer is "no
 * extension" rather than a partial one: a weaver that cannot see the whole hierarchy must leave
 * the call alone, and a call left alone fails loudly at run time rather than quietly doing
 * something else. A call naming exactly a declared receiver never reaches the walk at all, and is
 * answered directly even with no hierarchy to read.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable except for the two caches, both concurrent, so an instance may be shared by every
 * thread a parallel-capable class loader weaves on. The mapping function of {@code resolved}
 * populates {@code types} and never {@code resolved} itself, which is a recursion a
 * {@code ConcurrentHashMap} does not permit.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ExtensionIndex {

    /**
     * The index of a run that has no extensions, returned rather than allocated.
     *
     * <p>{@link #isEmpty()} answers {@code true} for it, and that is the question asked before a
     * class is parsed for extension calls.
     */
    public static final ExtensionIndex EMPTY =
            new ExtensionIndex(Map.of(), Map.of(), Map.of(), Set.of(), ClassSource.NONE);

    /** Every accepted declaration, keyed by the call it replaces: receiver, name and descriptor. */
    private final Map<Call, WeaveManifest.Extension> byCall;

    /** The declarations grouped by extended type, for the stub generator. */
    private final Map<String, List<WeaveManifest.Extension>> byReceiver;

    /** The declarations grouped by the class that implements them, for the receiver guards. */
    private final Map<String, List<WeaveManifest.Extension>> byHolder;

    /**
     * Every accepted name and descriptor, concatenated.
     *
     * <p>The owner is not part of it, so this dismisses a call no extension could answer with one
     * set lookup and no hierarchy walk. Method and field descriptors share the set safely: a
     * method's begins with {@code (} and a field's never does.
     */
    private final Set<String> signatures;

    /** Where supertypes are read from; {@code ClassSource.NONE} means nothing can be walked. */
    private final ClassSource hierarchy;

    /** Answers to queries the direct lookup missed, so a hierarchy is walked once per query. */
    private final Map<Query, Optional<WeaveManifest.Extension>> resolved =
            new ConcurrentHashMap<>();

    /** What each class file said, so a supertype is parsed once however many calls reach it. */
    private final Map<String, Type> types = new ConcurrentHashMap<>();

    /**
     * Takes the prepared maps as they are.
     *
     * <p>Nothing is copied or checked here; {@link #of(List, ClassSource, Reporter)} hands over
     * maps it has already frozen.
     *
     * @param byCall     declarations keyed by the call they replace
     * @param byReceiver declarations grouped by extended type
     * @param byHolder   declarations grouped by implementing class
     * @param signatures every accepted name and descriptor
     * @param hierarchy  where supertypes are read from
     */
    private ExtensionIndex(@NotNull final Map<Call, WeaveManifest.Extension> byCall,
                           @NotNull final Map<String, List<WeaveManifest.Extension>> byReceiver,
                           @NotNull final Map<String, List<WeaveManifest.Extension>> byHolder,
                           @NotNull final Set<String> signatures,
                           @NotNull final ClassSource hierarchy) {
        this.byCall = byCall;
        this.byReceiver = byReceiver;
        this.byHolder = byHolder;
        this.signatures = signatures;
        this.hierarchy = hierarchy;
    }

    /**
     * Builds an index that cannot walk a hierarchy and reports nothing.
     *
     * <p>Duplicates are still dropped, and shadowing is not detected at all, since detecting it
     * needs the receiver's class file. Only a call naming exactly the declared receiver is
     * answered.
     *
     * @param declared the declarations to index; must not be {@code null}
     * @return the index, or {@link #EMPTY} when nothing survives
     * @throws NullPointerException if {@code declared} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static ExtensionIndex of(@NotNull final List<WeaveManifest.Extension> declared) {
        return of(declared, ClassSource.NONE, Reporter.NOOP);
    }

    /**
     * Builds an index over the declarations found on a classpath.
     *
     * <p>Two kinds of declaration are refused here rather than at a call site, because both would
     * otherwise produce a rewrite whose outcome depends on the order the manifests were read in or
     * on a resolution {@code javac} had already made:
     *
     * <ul>
     *   <li>{@code AW1308} when two declarations contribute the same call, whether or not they come
     *       from different holders. The first declaration read stands; the second is refused rather
     *       than winning, and one of them has to be removed or renamed.
     *   <li>{@code AW1309} when the receiver, or something it inherits from, really declares a
     *       method of that name and descriptor. The extension is unreachable, and the declaration
     *       has to go.
     * </ul>
     *
     * <p>{@code AW1309} alone depends on the classpath: a receiver that is not on it makes
     * {@link #shadowedByReceiver} answer {@code false}, so the declaration is kept, unexamined.
     * {@code AW1308} is checked first and does not consult {@code receivers} at all, so two
     * declarations of the same call are refused whether or not the receiver can be read.
     *
     * @param declared  the declarations to index; must not be {@code null}
     * @param receivers where receiver class files are read from; must not be {@code null}
     * @param reporter  where a refusal is reported; must not be {@code null}
     * @return the index, or {@link #EMPTY} when nothing survives
     * @throws NullPointerException if any argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static ExtensionIndex of(@NotNull final List<WeaveManifest.Extension> declared,
                                    @NotNull final ClassSource receivers,
                                    @NotNull final Reporter reporter) {
        Objects.requireNonNull(declared, "declared");
        Objects.requireNonNull(receivers, "receivers");
        Objects.requireNonNull(reporter, "reporter");
        if (declared.isEmpty()) {
            return EMPTY;
        }

        final Map<Call, WeaveManifest.Extension> byCall = new LinkedHashMap<>();
        final Map<String, List<WeaveManifest.Extension>> byReceiver = new LinkedHashMap<>();
        final Map<String, List<WeaveManifest.Extension>> byHolder = new LinkedHashMap<>();
        for (final WeaveManifest.Extension extension : declared) {
            final Call call = Call.of(extension);

            final WeaveManifest.Extension existing = byCall.get(call);
            if (existing != null) {
                // Both would rewrite the same instruction, and the winner would be whichever
                // manifest the classpath happened to yield last. Refuse rather than pick.
                reporter.report(Diagnostic.builder(DiagnosticCode.DUPLICATE_EXTENSION)
                        .message(extension.receiver() + '.' + extension.name()
                                + " is contributed by both " + existing.className()
                                + " and " + extension.className())
                        .detail("both would rewrite the same call, and which one won would depend "
                                + "on the order the manifests were found in")
                        .remedy("remove one of them, or rename it so the two calls differ")
                        .build());
                continue;
            }

            if (shadowedByReceiver(extension, receivers, reporter)) {
                continue;
            }

            byCall.put(call, extension);
            byReceiver.computeIfAbsent(extension.receiverInternalName(), key -> new ArrayList<>())
                    .add(extension);
            byHolder.computeIfAbsent(extension.classInternalName(), key -> new ArrayList<>())
                    .add(extension);
        }

        if (byCall.isEmpty()) {
            return EMPTY;
        }
        final Map<String, List<WeaveManifest.Extension>> frozen = new LinkedHashMap<>();
        byReceiver.forEach((receiver, list) -> frozen.put(receiver, List.copyOf(list)));
        final Map<String, List<WeaveManifest.Extension>> holders = new LinkedHashMap<>();
        byHolder.forEach((holder, list) -> holders.put(holder, List.copyOf(list)));
        final Set<String> signatures = new HashSet<>();
        byCall.keySet().forEach(call -> signatures.add(call.name() + call.descriptor()));
        return new ExtensionIndex(Map.copyOf(byCall), Collections.unmodifiableMap(frozen),
                Collections.unmodifiableMap(holders), Set.copyOf(signatures), receivers);
    }

    /**
     * Reports whether the receiver already answers this call, and says so as {@code AW1309} if it
     * does.
     *
     * @param extension the declaration to check
     * @param receivers where receiver class files are read from
     * @param reporter  where the refusal is reported
     * @return {@code true} when the declaration must be dropped
     */
    private static boolean shadowedByReceiver(@NotNull final WeaveManifest.Extension extension,
                                              @NotNull final ClassSource receivers,
                                              @NotNull final Reporter reporter) {
        final String owner = declarerOf(receivers, extension.receiverInternalName(),
                extension.name(), extension.descriptor(), new HashSet<>());
        if (owner == null) {
            return false;
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_SHADOWED_AT_CALL_SITE)
                .message(extension.receiver() + '.' + extension.name() + extension.descriptor()
                        + " is a real method of " + owner.replace('/', '.')
                        + ", so the extension in " + extension.className() + " is never reached")
                .detail("javac resolves the call to the real method, and rewriting it would "
                        + "redirect a call that was already correct")
                .remedy("delete the extension, or rename it so it no longer collides")
                .build());
        return true;
    }

    /**
     * Finds the type in a hierarchy that declares a method of a given name and descriptor.
     *
     * <p>Methods only; {@link ClassModel#fields()} is never consulted here, unlike {@link #read},
     * so a field the receiver declares does not shadow an extension. Superclasses and interfaces
     * both, and the {@code seen} set is what stops an interface reachable by two paths from being
     * read twice and a malformed hierarchy from recursing forever. Depth-first here, unlike the
     * resolution walk, because the answer is only whether some type declares the method and not
     * which one wins.
     *
     * <p>A class that is absent or unreadable ends that branch silently. The processor checked at
     * compile time, and a diagnostic for "could not look" would fire on every build that weaves
     * without the JDK's own class files to hand.
     *
     * @param source     where class files are read from
     * @param internal   the type to search from
     * @param name       the method name
     * @param descriptor the method descriptor
     * @param seen       the types already visited, added to as the walk proceeds
     * @return the internal name of the declaring type, or {@code null} when no method of that
     *         name and descriptor was found
     */
    @Nullable
    private static String declarerOf(@NotNull final ClassSource source,
                                     @NotNull final String internal,
                                     @NotNull final String name,
                                     @NotNull final String descriptor,
                                     @NotNull final Set<String> seen) {
        if (!seen.add(internal)) {
            return null;
        }
        final Optional<byte[]> bytes = source.find(internal);
        if (bytes.isEmpty()) {
            // Not on this classpath. Silence is right: the processor checked at compile time, and
            // inventing a diagnostic for "could not look" would fire on every build that weaves
            // without the JDK's own class files to hand.
            return null;
        }

        final ClassModel model;
        try {
            model = ClassFile.of().parse(bytes.get());
        } catch (final IllegalArgumentException unreadable) {
            return null;
        }
        for (final MethodModel method : model.methods()) {
            if (method.methodName().equalsString(name)
                    && method.methodType().equalsString(descriptor)) {
                return internal;
            }
        }

        final List<String> supertypes = new ArrayList<>();
        model.superclass().ifPresent(superclass -> supertypes.add(superclass.asInternalName()));
        model.interfaces().forEach(each -> supertypes.add(each.asInternalName()));
        for (final String supertype : supertypes) {
            final String declarer = declarerOf(source, supertype, name, descriptor, seen);
            if (declarer != null) {
                return declarer;
            }
        }
        return null;
    }

    /**
     * Reports whether any declaration has this name and descriptor, whatever its receiver.
     *
     * <p>The cheap pre-filter: a constant pool can be scanned with it without resolving anything.
     *
     * @param name       the member name
     * @param descriptor the descriptor as written at the call site
     * @return {@code true} when some declaration could match
     */
    @Contract(pure = true)
    public boolean mentions(@NotNull final String name, @NotNull final String descriptor) {
        return this.signatures.contains(name + descriptor);
    }

    /**
     * Looks a call up on exactly this receiver, with no hierarchy walk and no kind check.
     *
     * @param receiver   the receiver's internal name
     * @param name       the member name
     * @param descriptor the descriptor as written at the call site
     * @return the declaration, or {@code null} when this receiver has none
     */
    @Contract(pure = true)
    @Nullable
    public WeaveManifest.Extension declaredOn(@NotNull final String receiver,
                                              @NotNull final String name,
                                              @NotNull final String descriptor) {
        return this.byCall.get(new Call(receiver, name, descriptor));
    }

    /**
     * Answers which extension a call site should be rewritten to reach.
     *
     * <p>Three steps, cheapest first: a name and descriptor no declaration has is dismissed by
     * {@link #mentions(String, String)}; a call naming the declared receiver exactly is answered
     * from the map; and only what remains walks the owner's hierarchy, once per distinct query.
     *
     * <p>The kind must agree in every case. An instance extension does not answer a static call or
     * the other way round, and a mismatch answers {@code null} rather than continuing to look
     * further up.
     *
     * @param owner      the owner written at the call site, as an internal name
     * @param name       the member name
     * @param descriptor the descriptor as written at the call site
     * @param kind       the shape of the call site
     * @return the extension to rewrite to, or {@code null} when the call must be left alone
     */
    @Contract(pure = true)
    @Nullable
    public WeaveManifest.Extension find(@NotNull final String owner,
                                        @NotNull final String name,
                                        @NotNull final String descriptor,
                                        @NotNull final WeaveManifest.Extension.Kind kind) {
        if (!mentions(name, descriptor)) {
            return null;
        }
        final WeaveManifest.Extension direct = this.byCall.get(new Call(owner, name, descriptor));
        if (direct != null) {
            return direct.kind() == kind ? direct : null;
        }
        return this.resolved
                .computeIfAbsent(new Query(owner, name, descriptor, kind), this::walk)
                .orElse(null);
    }

    /**
     * Resolves a query against the owner's hierarchy, in resolution order.
     *
     * <p>The first type that answers ends the walk, whether it answers with an extension or with a
     * member of its own. That ordering is the whole point: an extension on a supertype is reached
     * only when nothing nearer declares the member.
     *
     * @param query the call to resolve
     * @return the extension, or empty when the call must be left alone
     */
    @Contract(pure = true)
    @NotNull
    private Optional<WeaveManifest.Extension> walk(@NotNull final Query query) {
        final List<String> hierarchy = hierarchyOf(query.owner(), query.kind());
        if (hierarchy == null) {
            // A supertype was not on this classpath. The same silence as everywhere else in this
            // file: a weaver that cannot see the whole hierarchy must leave the call alone, and a
            // call left alone fails loudly at runtime rather than quietly doing something else.
            return Optional.empty();
        }

        final String signature = query.name() + query.descriptor();
        for (final String internal : hierarchy) {
            final WeaveManifest.Extension contributed =
                    this.byCall.get(new Call(internal, query.name(), query.descriptor()));
            if (contributed != null) {
                return contributed.kind() == query.kind()
                        ? Optional.of(contributed)
                        : Optional.empty();
            }
            final Type type = typeOf(internal);
            if (type != null && type.members().contains(signature)) {
                // A real method, found before any extension. javac resolved the call to this, and
                // rewriting here would redirect a call that was already correct.
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Lists the types a member lookup on this owner would consult, nearest first.
     *
     * <p>Superclasses first, then interfaces breadth-first. The interface list is appended to while
     * it is being indexed through, which is how a super-interface joins the queue behind the
     * interfaces already found; an enhanced for loop over it would fail instead.
     *
     * <p>A static call stops at the superclass chain, because a static method of an interface is
     * not inherited. Constants and instance members both are, so they continue into the interfaces
     * — {@code C.X} does resolve to an interface's field.
     *
     * <p>Answering {@code null} rather than a partial list is what makes an unreadable supertype
     * mean "leave the call alone" instead of "no extension here".
     *
     * @param owner the type written at the call site
     * @param kind  the shape of the call site
     * @return the types to consult, or {@code null} when some of the hierarchy could not be read
     */
    @Contract(pure = true)
    @Nullable
    private List<String> hierarchyOf(@NotNull final String owner,
                                     @NotNull final WeaveManifest.Extension.Kind kind) {
        final List<String> order = new ArrayList<>();
        final List<String> interfaces = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        String current = owner;
        while (current != null && seen.add(current)) {
            final Type type = typeOf(current);
            if (type == null) {
                return null;
            }
            order.add(current);
            interfaces.addAll(type.interfaces());
            current = type.superclass();
        }
        if (kind == WeaveManifest.Extension.Kind.STATIC) {
            return order;
        }
        // Constants and instance members both inherit through interfaces — `C.X` resolves to an
        // interface's field, and only a static *method* of an interface is not inherited.

        for (int i = 0; i < interfaces.size(); i++) {
            final String each = interfaces.get(i);
            if (!seen.add(each)) {
                continue;
            }
            final Type type = typeOf(each);
            if (type == null) {
                return null;
            }
            order.add(each);
            interfaces.addAll(type.interfaces());
        }
        return order;
    }

    /**
     * Returns what a class file said, reading it at most once.
     *
     * <p>The sentinel is unwrapped here so that no caller has to know the cache cannot hold
     * {@code null}.
     *
     * @param internal the type's internal name
     * @return the type, or {@code null} when it could not be read
     */
    @Contract(pure = true)
    @Nullable
    private Type typeOf(@NotNull final String internal) {
        final Type type = this.types.computeIfAbsent(internal, this::read);
        return type == Type.UNKNOWN ? null : type;
    }

    /**
     * Parses one class file into the little that resolution needs.
     *
     * <p>An absent or unparseable class becomes the sentinel rather than an exception, so a
     * classpath that does not contain the whole world costs a rewrite rather than a class.
     *
     * <p>Members are recorded as name and descriptor concatenated; the declared modifiers are not
     * read, so a private member of a supertype counts here as one the call could have resolved to.
     *
     * @param internal the type's internal name
     * @return what the class file said, or the sentinel
     */
    @Contract(pure = true)
    @NotNull
    private Type read(@NotNull final String internal) {
        final Optional<byte[]> bytes = this.hierarchy.find(internal);
        if (bytes.isEmpty()) {
            return Type.UNKNOWN;
        }
        final ClassModel model;
        try {
            model = ClassFile.of().parse(bytes.get());
        } catch (final IllegalArgumentException unreadable) {
            return Type.UNKNOWN;
        }

        final Set<String> members = new HashSet<>();
        for (final MethodModel method : model.methods()) {
            members.add(method.methodName().stringValue() + method.methodType().stringValue());
        }
        // Fields in the same set as methods, which is safe because the two descriptor forms cannot
        // collide: a method's begins with '(' and a field's never does.
        for (final FieldModel field : model.fields()) {
            members.add(field.fieldName().stringValue() + field.fieldType().stringValue());
        }
        final List<String> interfaces = new ArrayList<>();
        model.interfaces().forEach(each -> interfaces.add(each.asInternalName()));
        return new Type(model.superclass().map(entry -> entry.asInternalName()).orElse(null),
                List.copyOf(interfaces), Set.copyOf(members));
    }

    /**
     * Reports whether this index would rewrite nothing.
     *
     * @return {@code true} when no declaration was accepted
     */
    @Contract(pure = true)
    public boolean isEmpty() {
        return this.byCall.isEmpty();
    }

    /**
     * Returns how many calls this index can answer for.
     *
     * @return the number of accepted declarations
     */
    @Contract(pure = true)
    public int size() {
        return this.byCall.size();
    }

    /**
     * Returns the extended types, in the order their first declaration was read.
     *
     * <p>A fresh copy per call, which is what a caller that writes a stub per receiver iterates.
     *
     * @return the receivers, as internal names
     */
    @Contract(pure = true)
    @NotNull
    @Unmodifiable
    public Set<String> receivers() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.byReceiver.keySet()));
    }

    /**
     * Returns what one holder contributes, which is what the receiver guards are woven from.
     *
     * @param holder the holder's internal name
     * @return the declarations, empty when that class contributes none
     */
    @Contract(pure = true)
    @NotNull
    @Unmodifiable
    public List<WeaveManifest.Extension> declaredBy(@NotNull final String holder) {
        return this.byHolder.getOrDefault(holder, List.of());
    }

    /**
     * Returns what one extended type gains, which is what a stub is generated from.
     *
     * @param receiver the receiver's internal name
     * @return the declarations, empty when nothing extends that type
     */
    @Contract(pure = true)
    @NotNull
    @Unmodifiable
    public List<WeaveManifest.Extension> contributedTo(@NotNull final String receiver) {
        return this.byReceiver.getOrDefault(receiver, List.of());
    }

    /**
     * Returns how many declarations this index holds and how many types they extend.
     *
     * @return a description of this index
     */
    @Override
    @NotNull
    public String toString() {
        return "ExtensionIndex[" + this.byCall.size() + " method"
                + (this.byCall.size() == 1 ? "" : "s") + " on "
                + this.byReceiver.size() + " receiver"
                + (this.byReceiver.size() == 1 ? "" : "s") + ']';
    }

    /**
     * The call an extension replaces, as written at the call site.
     *
     * <p>{@code descriptor} is the call-site form and not the implementation's, so a lookup can be
     * made straight from an instruction without adjusting for the receiver.
     *
     * @param owner      the receiver's internal name
     * @param name       the member name
     * @param descriptor the descriptor as written at the call site
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Call(@NotNull String owner,
                        @NotNull String name,
                        @NotNull String descriptor) {

        /**
         * Returns the call a declaration replaces.
         *
         * @param extension the declaration
         * @return the call
         */
        @Contract(value = "_ -> new", pure = true)
        @NotNull
        static Call of(@NotNull final WeaveManifest.Extension extension) {
            return new Call(extension.receiverInternalName(), extension.name(),
                    extension.descriptor());
        }
    }

    /**
     * A call whose owner is not itself an extended type, and the key its answer is cached under.
     *
     * <p>The kind is part of the key because it is part of the answer: the same owner, name and
     * descriptor resolve differently for a static call and an instance one.
     *
     * @param owner      the owner written at the call site
     * @param name       the member name
     * @param descriptor the descriptor as written at the call site
     * @param kind       the shape of the call site
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Query(@NotNull String owner,
                         @NotNull String name,
                         @NotNull String descriptor,
                         @NotNull WeaveManifest.Extension.Kind kind) {
    }

    /**
     * As much of a class file as resolution needs: what it extends, what it implements, and what it
     * declares.
     *
     * <p>Fields and methods share one member set, which is safe because the two descriptor forms
     * cannot collide: a method's begins with {@code (} and a field's never does.
     *
     * @param superclass the superclass's internal name, {@code null} only for a class file that
     *                   names none
     * @param interfaces the directly implemented interfaces, as internal names
     * @param members    every declared member, as name and descriptor concatenated
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Type(@Nullable String superclass,
                        @NotNull @Unmodifiable List<String> interfaces,
                        @NotNull @Unmodifiable Set<String> members) {

        /**
         * Stands for a class that could not be read.
         *
         * <p>A sentinel rather than {@code null}, because the cache is a
         * {@code ConcurrentHashMap} and cannot hold one.
         */
        static final Type UNKNOWN = new Type(null, List.of(), Set.of());
    }
}
