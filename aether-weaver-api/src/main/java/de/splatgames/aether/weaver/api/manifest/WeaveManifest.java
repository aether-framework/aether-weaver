package de.splatgames.aether.weaver.api.manifest;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import de.splatgames.aether.weaver.api.experimental.Nulls;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.experimental.Scope;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The contents of one artefact's weave manifest: every weave class it declares and every
 * extension it contributes.
 *
 * <p>A manifest is a JSON document carried inside a jar or a class directory at
 * {@value #RESOURCE}. It is written once, at compile time, by the annotation processor, and read
 * much later by whatever assembles a weaving plan. Its purpose is to make weaves discoverable
 * without loading and scanning every class on the classpath: one resource per classpath root
 * names the weave classes in that root, the classes they target, and what each of them declares.
 *
 * <p>This record is the parsed form of that document. {@link ManifestWriter#write(WeaveManifest)}
 * renders it and {@link ManifestReader#read(String, String, de.splatgames.aether.weaver.api.spi.Reporter)}
 * parses it; the two are inverses for any manifest whose version this release reads.
 *
 * <h2>The document</h2>
 *
 * <p>The root is a JSON object with four keys, written in this order:
 *
 * <ul>
 *   <li>{@code "version"} — the schema version as an integer, {@value #VERSION} for a manifest
 *       this release writes. See the compatibility rules below.
 *   <li>{@code "generator"} — free text naming what produced the document. It is carried through
 *       parsing and used in nothing else.
 *   <li>{@code "weaves"} — an array of weave objects, in declaration order.
 *   <li>{@code "extensions"} — an array of extension objects, in declaration order.
 * </ul>
 *
 * <p>Every nested object has fixed key order too, because the rendered text has to be stable
 * between builds. A weave object holds {@code "class"}, {@code "kind"}, {@code "priority"},
 * {@code "require"}, {@code "phase"}, {@code "tags"}, {@code "targets"}, {@code "members"} and
 * {@code "injectors"}; a member holds {@code "disposition"}, {@code "kind"}, {@code "name"},
 * {@code "descriptor"}, {@code "targetName"} and {@code "unique"}; an injector holds
 * {@code "kind"}, {@code "id"}, {@code "handler"}, {@code "method"}, {@code "points"},
 * {@code "require"}, {@code "allow"} and {@code "group"}; a point holds {@code "point"},
 * {@code "target"}, {@code "ordinal"}, {@code "shift"}, {@code "by"}, {@code "access"} and
 * {@code "slice"}; an extension holds {@code "class"}, {@code "receiver"}, {@code "name"},
 * {@code "descriptor"} and, only where they differ from their defaults, {@code "kind"},
 * {@code "require"}, {@code "nulls"} and {@code "scope"}.
 *
 * <p>The key {@code "class"} carries what {@link Weave#className()} and
 * {@link Extension#className()} return; every other key is spelled exactly like the record
 * component it holds.
 *
 * <h2>A complete document</h2>
 *
 * <p>One weave with one shadowed field and one injector, and one extension, rendered by
 * {@link ManifestWriter}:
 *
 * <pre>
 * {
 *   "version": 1,
 *   "generator": "aether-weaver-processor/0.1.0",
 *   "weaves": [
 *     {
 *       "class": "com.acme.audit.PaymentAudit",
 *       "kind": "INSTANCE",
 *       "priority": 100,
 *       "require": "REQUIRED",
 *       "phase": "DEFAULT",
 *       "tags": ["audit"],
 *       "targets": ["com.acme.PaymentService"],
 *       "members": [
 *         {
 *           "disposition": "SHADOW",
 *           "kind": "FIELD",
 *           "name": "ledger",
 *           "descriptor": "Lcom/acme/Ledger;",
 *           "targetName": "ledger",
 *           "unique": false
 *         }
 *       ],
 *       "injectors": [
 *         {
 *           "kind": "INJECT",
 *           "id": "onCharge",
 *           "handler": "onCharge(Ljava/math/BigDecimal;)V",
 *           "method": "com.acme.PaymentService.charge(java.math.BigDecimal)",
 *           "points": [
 *             {
 *               "point": "HEAD",
 *               "target": "",
 *               "ordinal": -1,
 *               "shift": "NONE",
 *               "by": 0,
 *               "access": "ANY",
 *               "slice": ""
 *             }
 *           ],
 *           "require": 1,
 *           "allow": 0,
 *           "group": ""
 *         }
 *       ]
 *     }
 *   ],
 *   "extensions": [
 *     {
 *       "class": "com.acme.Strings",
 *       "receiver": "java.lang.String",
 *       "name": "shout",
 *       "descriptor": "()Ljava/lang/String;"
 *     }
 *   ]
 * }
 * </pre>
 *
 * <h2>Reading a field that is not recognised</h2>
 *
 * <p>A reader ignores any key it does not know, at every level of the document — root, weave,
 * member, injector, point and extension alike — and reports nothing. An entry of an array that
 * is not a JSON object is dropped without a diagnostic, and so is an element of
 * {@code "tags"} or {@code "targets"} that is not a string.
 *
 * <p>Omitting a key and writing its default are the same statement: an entry that leaves out
 * {@code "priority"} reads back equal to one that writes {@code 0}. The defaults are listed on
 * {@link ManifestReader}.
 *
 * <h2>What may change without breaking a reader</h2>
 *
 * <ul>
 *   <li><b>Adding a key</b> is compatible in both directions. An older reader ignores it, and a
 *       newer reader supplies its default when an older document omits it.
 *   <li><b>Removing a key</b> is compatible only where the reader's default for it means what
 *       the omitted value meant.
 *   <li><b>Adding a value to a field that spells out an enumeration constant</b> is compatible
 *       for the extension policies {@code "require"}, {@code "nulls"} and {@code "scope"}: an
 *       unrecognised value costs an {@code AW2300} diagnostic and the entry is kept with the
 *       default. It is not compatible for an extension {@code "kind"}, where an unrecognised
 *       value costs that entry, again reported as {@code AW2300}. The strings inside a weave
 *       entry are not checked against anything by this type.
 *   <li><b>Giving an existing key a new meaning</b> requires raising {@link #VERSION}, which
 *       costs every older reader the whole document: a manifest whose {@code "version"} exceeds
 *       the reader's is refused as {@code AW2301} rather than interpreted.
 * </ul>
 *
 * <h2>Order, and what it decides</h2>
 *
 * <p>Every list here keeps the order it was given. The writer emits arrays in list order and
 * iterates no hash-ordered collection, so the same manifest renders byte-identically every time,
 * and the reader returns entries in document order.
 *
 * <p>Order is also part of equality. These are records, so equality compares every component of
 * every nested entry, and list equality is order-sensitive: two manifests holding the same
 * weaves in a different order are not equal.
 *
 * <h2>Absent, and empty</h2>
 *
 * <p>An absent array and an empty one parse to the same empty list and cannot be told apart
 * afterwards. The distinction that does survive is between an artefact with an empty manifest
 * and an artefact with no manifest resource at all: the annotation processor writes no resource
 * for a module that declares neither a weave nor an extension, so a missing
 * {@value #RESOURCE} is the signal that a module was compiled without the processor rather than
 * a module that had nothing to say.
 *
 * <h2>Immutability</h2>
 *
 * <p>This record and all of its nested records are immutable. Every list component is copied
 * with {@link List#copyOf(java.util.Collection)} on construction, so a list handed in afterwards
 * cannot be changed through the manifest, and the returned lists reject modification. Instances
 * may be shared between threads without synchronisation. The copy also rejects a {@code null}
 * element with a {@link NullPointerException}.
 *
 * @param version    the schema version the manifest states; positive
 * @param generator  free text naming what produced the manifest
 * @param weaves     the weave classes this artefact declares, in declaration order
 * @param extensions the extensions this artefact contributes, in declaration order
 * @author Erik Pförtner
 * @since 0.1.0
 * @see ManifestReader
 * @see ManifestWriter
 */
public record WeaveManifest(int version,
                            @NotNull String generator,
                            @NotNull @Unmodifiable List<Weave> weaves,
                            @NotNull @Unmodifiable List<Extension> extensions) {

    /**
     * The schema version this release writes, and the highest it reads.
     *
     * <p>A document stating a higher version is refused whole by
     * {@link ManifestReader#read(String, String, de.splatgames.aether.weaver.api.spi.Reporter)}
     * with {@code AW2301}. A document omitting {@code "version"} is read as this value. No version
     * below it is currently readable either: {@link ManifestReader} maps a stated {@code 0} to
     * this constant and rejects anything that resolves to a negative one, so with the constant at
     * {@code 1} today, every manifest {@link ManifestReader} returns states version {@code 1}.
     * This field is kept separate from that constant so that a later release, once it has raised
     * this value, can still read an older document at the version it actually states rather than
     * one that has been silently upgraded.
     */
    public static final int VERSION = 1;

    /**
     * The path a manifest occupies inside a jar or a class directory.
     *
     * <p>Discovery looks for this exact resource on every classpath root, so a manifest anywhere
     * else is not found at all.
     */
    public static final String RESOURCE = "META-INF/aether/weaves.json";

    /**
     * Copies both lists and rejects a manifest that could not have been written by any version.
     *
     * @throws NullPointerException     if {@code generator}, {@code weaves} or {@code extensions}
     *                                  is {@code null}, or either list holds {@code null}
     * @throws IllegalArgumentException if {@code version} is not positive
     */
    public WeaveManifest {
        Objects.requireNonNull(generator, "generator");
        weaves = List.copyOf(Objects.requireNonNull(weaves, "weaves"));
        extensions = List.copyOf(Objects.requireNonNull(extensions, "extensions"));
        if (version <= 0) {
            throw new IllegalArgumentException("a manifest version must be positive, was " + version);
        }
    }

    /**
     * Creates a manifest of the current schema version that contributes no extension.
     *
     * @param generator free text naming what produced the manifest
     * @param weaves    the weave classes to record, in the order they are to be written
     * @return a manifest at version {@value #VERSION} with an empty extension list
     * @throws NullPointerException if either argument is {@code null} or {@code weaves} holds
     *                              {@code null}
     */
    @Contract(value = "_, _ -> new", pure = true)
    @NotNull
    public static WeaveManifest of(@NotNull final String generator,
                                   @NotNull final List<Weave> weaves) {
        return of(generator, weaves, List.of());
    }

    /**
     * Creates a manifest of the current schema version.
     *
     * @param generator  free text naming what produced the manifest
     * @param weaves     the weave classes to record, in the order they are to be written
     * @param extensions the extensions to record, in the order they are to be written
     * @return a manifest at version {@value #VERSION}
     * @throws NullPointerException if any argument is {@code null} or either list holds
     *                              {@code null}
     */
    @Contract(value = "_, _, _ -> new", pure = true)
    @NotNull
    public static WeaveManifest of(@NotNull final String generator,
                                   @NotNull final List<Weave> weaves,
                                   @NotNull final List<Extension> extensions) {
        return new WeaveManifest(VERSION, generator, weaves, extensions);
    }

    /**
     * Combines this manifest with another, letting the other win wherever both describe the same
     * class.
     *
     * <p>Weaves are matched by {@link Weave#className()}. An entry of {@code other} whose class
     * this manifest also names replaces it and keeps its position; an entry naming a class this
     * manifest does not mention is appended after all of them. Merging is therefore how an
     * incremental build keeps the weaves it did not recompile: a manifest holding only the
     * recompiled classes, merged over the previous one, leaves the untouched entries alone.
     *
     * <p>Extensions are matched by {@link Extension#className()} — the holder, not the receiver
     * and not the member — and at that granularity only: if {@code other} declares any extension
     * on a holder, every extension this manifest declares on that same holder is dropped before
     * the other's are appended. A holder that splits its extensions across two manifests loses
     * one half of them, so all extensions of one holder belong in one manifest.
     *
     * <p>The result takes the higher of the two versions and the generator of {@code other}.
     * Neither operand is modified.
     *
     * @param other the manifest whose entries win; must not be {@code null}
     * @return a new manifest holding the entries of both
     * @throws NullPointerException if {@code other} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public WeaveManifest merge(@NotNull final WeaveManifest other) {
        Objects.requireNonNull(other, "other");
        final Map<String, Weave> byClass = new LinkedHashMap<>();
        for (final Weave weave : this.weaves) {
            byClass.put(weave.className(), weave);
        }
        for (final Weave weave : other.weaves) {
            byClass.put(weave.className(), weave);
        }

        final Set<String> replaced = new LinkedHashSet<>();
        for (final Extension extension : other.extensions) {
            replaced.add(extension.className());
        }
        final List<Extension> extensions = new ArrayList<>();
        for (final Extension extension : this.extensions) {
            if (!replaced.contains(extension.className())) {
                extensions.add(extension);
            }
        }
        extensions.addAll(other.extensions);

        return new WeaveManifest(Math.max(this.version, other.version),
                other.generator, new ArrayList<>(byClass.values()), extensions);
    }

    /**
     * Returns the version, the generator and how many entries each list holds.
     *
     * <p>The entries themselves are left out: a manifest of any size is meant to stay one line in
     * a log. {@link ManifestWriter#write(WeaveManifest)} renders the contents.
     *
     * @return a one-line summary of this manifest
     */
    @Override
    @NotNull
    public String toString() {
        return "WeaveManifest[version=" + this.version + ", generator=" + this.generator
                + ", weaves=" + this.weaves.size()
                + ", extensions=" + this.extensions.size() + ']';
    }

    /**
     * One weave class, with everything it declares.
     *
     * <p>The strings that spell out an enumeration constant — {@link #kind()},
     * {@link #require()} and {@link #phase()} — are carried as text and are not checked against
     * any enumeration by this record. A manifest may therefore name a constant this release does
     * not know, and what happens then is decided by whoever consumes the entry, not here.
     *
     * @param className the weave class, as a binary name such as
     *                  {@code com.acme.audit.PaymentAudit}; must not be blank
     * @param kind      the name of the {@link de.splatgames.aether.weaver.api.Weave.Kind}
     *                  constant the weave declares, {@code "INSTANCE"} or {@code "STATIC"}
     * @param priority  the weave's priority, which orders declarations meeting at one place; a
     *                  higher value comes first
     * @param require   the name of the {@link Require} constant the weave declares,
     *                  {@code "REQUIRED"} or {@code "OPTIONAL"}
     * @param phase     the name of the {@link de.splatgames.aether.weaver.api.Phase} constant the
     *                  weave declares, {@code "EARLY"} or {@code "DEFAULT"}
     * @param tags      the weave's tags, in declaration order; empty when it declares none
     * @param targets   the classes this weave applies to, as binary names, in declaration order
     * @param members   the fields and methods the weave contributes to or borrows from its
     *                  targets, excluding handler methods
     * @param injectors the injection declarations of the weave, in declaration order
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Weave(@NotNull String className,
                        @NotNull String kind,
                        int priority,
                        @NotNull String require,
                        @NotNull String phase,
                        @NotNull @Unmodifiable List<String> tags,
                        @NotNull @Unmodifiable List<String> targets,
                        @NotNull @Unmodifiable List<Member> members,
                        @NotNull @Unmodifiable List<Injector> injectors) {

        /**
         * Copies the four lists and rejects an entry that names no class.
         *
         * <p>A blank class name is refused because the class name is the identity of the entry:
         * it is what merging matches on and what discovery reports a duplicate under.
         *
         * @throws NullPointerException     if any argument is {@code null} or any list holds
         *                                  {@code null}
         * @throws IllegalArgumentException if {@code className} is blank
         */
        public Weave {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(require, "require");
            Objects.requireNonNull(phase, "phase");
            if (className.isBlank()) {
                throw new IllegalArgumentException("a weave class name must not be blank");
            }
            tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            members = List.copyOf(Objects.requireNonNull(members, "members"));
            injectors = List.copyOf(Objects.requireNonNull(injectors, "injectors"));
        }
    }

    /**
     * One field or method of a weave class that is not a handler.
     *
     * <p>A handler method is recorded as an {@link Injector} instead, and never as a member as
     * well, so that one method never appears twice under two spellings that could drift apart.
     *
     * @param disposition how the member relates to the target: {@code "SHADOW"},
     *                    {@code "ACCESSOR"} and {@code "INVOKER"} for a member the weave declares
     *                    with the annotation of that name, and {@code "MERGE"} for one it
     *                    declares with none of them
     * @param kind        {@code "FIELD"} or {@code "METHOD"}
     * @param name        the member's name in the weave class; must not be blank
     * @param descriptor  the member's JVM descriptor, such as {@code Lcom/acme/Ledger;} for a
     *                    field or {@code ()Ljava/lang/String;} for a method
     * @param targetName  the name the member has in the target. Equal to {@code name} unless the
     *                    member is an {@code ACCESSOR} or {@code INVOKER} declared with no
     *                    explicit name, in which case it is {@code name} with a leading
     *                    {@code get}, {@code set} or {@code is} (accessor) or {@code call} or
     *                    {@code invoke} (invoker) removed and the following character
     *                    lower-cased — {@code getLedger} yields {@code ledger} — so the two can
     *                    differ even where nothing in the declaration names a rename
     * @param unique      whether the member is declared {@code @Unique}
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Member(@NotNull String disposition,
                         @NotNull String kind,
                         @NotNull String name,
                         @NotNull String descriptor,
                         @NotNull String targetName,
                         boolean unique) {

        /**
         * Rejects a member that has no name.
         *
         * @throws NullPointerException     if any argument is {@code null}
         * @throws IllegalArgumentException if {@code name} is blank
         */
        public Member {
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(targetName, "targetName");
            if (name.isBlank()) {
                throw new IllegalArgumentException("a member name must not be blank");
            }
        }
    }

    /**
     * One injection declaration of a weave: a handler, the method it applies to, and the places
     * inside that method where it applies.
     *
     * @param kind    the kind of injector, such as {@code "INJECT"}, {@code "REDIRECT"} or
     *                {@code "WRAP"}; a third-party kind keeps its {@code namespace:name} shape.
     *                The text is not checked against any known kind here
     * @param id      the identifier that names this declaration in diagnostics
     * @param handler the handler method of the weave class, written as its name immediately
     *                followed by its JVM descriptor, such as
     *                {@code onCharge(Ljava/math/BigDecimal;)V}; must not be blank
     * @param method  the selector naming the target method, in canonical form where the
     *                declaration could be resolved and as written otherwise; must not be blank
     * @param points  the injection points to look for, in declaration order
     * @param require the fewest matches that count as success
     * @param allow   the most matches that count as success; {@code 0} imposes no upper bound
     *                rather than forbidding every match
     * @param group   the group this declaration is accounted against, or an empty string to be
     *                accounted alone
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Injector(@NotNull String kind,
                           @NotNull String id,
                           @NotNull String handler,
                           @NotNull String method,
                           @NotNull @Unmodifiable List<Point> points,
                           int require,
                           int allow,
                           @NotNull String group) {

        /**
         * Copies the point list and rejects a declaration that cannot be acted on.
         *
         * <p>Both a handler and a target-method selector are required: an injector missing
         * either names no work that could be performed.
         *
         * @throws NullPointerException     if any argument is {@code null} or {@code points}
         *                                  holds {@code null}
         * @throws IllegalArgumentException if {@code handler} or {@code method} is blank
         */
        public Injector {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(group, "group");
            points = List.copyOf(Objects.requireNonNull(points, "points"));
            if (handler.isBlank() || method.isBlank()) {
                throw new IllegalArgumentException(
                        "an injector needs both a handler and a target-method selector");
            }
        }
    }

    /**
     * One place inside a target method that an {@link Injector} looks for.
     *
     * @param point   the identifier of the injection point, such as {@code "HEAD"} or
     *                {@code "INVOKE"}; must not be blank
     * @param target  the selector of the member the point looks for, always exactly as written in
     *                the declaration and never resolved to canonical form, or an empty string for
     *                a point that names none
     * @param ordinal which match to take: {@code -1} for every match, otherwise a zero-based
     *                index into the matches
     * @param shift   the name of the {@link de.splatgames.aether.weaver.api.At.Shift} constant
     *                the declaration asks for, such as {@code "NONE"} or {@code "BY"}
     * @param by      how far to shift when {@code shift} is {@code "BY"}
     * @param access  the name of the {@link de.splatgames.aether.weaver.api.At.Access} constant
     *                narrowing a field access, such as {@code "ANY"} or {@code "GET"}
     * @param slice   the identifier of the slice the search is narrowed to, or an empty string
     *                to search the whole method
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Point(@NotNull String point,
                        @NotNull String target,
                        int ordinal,
                        @NotNull String shift,
                        int by,
                        @NotNull String access,
                        @NotNull String slice) {

        /**
         * Rejects a point that names no injection point.
         *
         * <p>{@code ordinal} is not checked here; a value below {@code -1} is carried as written.
         *
         * @throws NullPointerException     if any argument is {@code null}
         * @throws IllegalArgumentException if {@code point} is blank
         */
        public Point {
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(shift, "shift");
            Objects.requireNonNull(access, "access");
            Objects.requireNonNull(slice, "slice");
            if (point.isBlank()) {
                throw new IllegalArgumentException("an injection point must be named");
            }
        }
    }

    /**
     * One member a holder class contributes to a type it does not own.
     *
     * <p>An extension makes a member appear on a type whose source the holder does not own: a
     * call written as {@code receiver.name(...)} is served by the member of {@link #className()}
     * that this entry names. What shape that call has is decided by {@link #kind()}, and that in
     * turn decides what {@link #descriptor()} means and what
     * {@link #implementationDescriptor()} returns.
     *
     * <p>{@link #descriptor()} is always the descriptor of the call as written at the call site.
     * For {@link Kind#INSTANCE} the receiver is not part of it, because at the call site the
     * receiver is on the stack rather than in the argument list; the descriptor the holder's
     * method actually has is {@link #implementationDescriptor()}.
     *
     * <p>{@link #require()}, {@link #nulls()} and {@link #scope()} are policies the declaration
     * states and this entry carries. Each has a default that is not written to the manifest at
     * all, so an entry holding only the first four fields is a {@link Require#REQUIRED},
     * {@link Nulls#UNCHECKED}, {@link Scope#PUBLIC} instance extension. {@link Nulls} and
     * {@link Scope} are experimental, and an entry naming a value of either that this release
     * does not know is kept with the default rather than dropped.
     *
     * @param className  the holder class declaring the extension, as a binary name; must not be
     *                   blank
     * @param receiver   the type the extension appears on, as a binary name such as
     *                   {@code java.lang.String}; must not be blank
     * @param name       the member's name as it is called; must not be blank
     * @param descriptor the JVM descriptor of the call site — a method descriptor for
     *                   {@link Kind#INSTANCE} and {@link Kind#STATIC}, and a field descriptor for
     *                   {@link Kind#CONSTANT}; must not be blank
     * @param kind       the shape of call this extension replaces
     * @param require    the {@link Require} policy the declaration states
     * @param nulls      the {@link Nulls} policy the declaration states for the receiver of an
     *                   instance extension, which {@link #guarded()} reads
     * @param scope      the {@link Scope} the declaration states
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Extension(@NotNull String className,
                            @NotNull String receiver,
                            @NotNull String name,
                            @NotNull String descriptor,
                            @NotNull Kind kind,
                            @NotNull Require require,
                            @NotNull Nulls nulls,
                            @NotNull Scope scope) {

        /**
         * Rejects an extension that is missing any of the four parts that identify it.
         *
         * @throws NullPointerException     if any argument is {@code null}
         * @throws IllegalArgumentException if {@code className}, {@code receiver}, {@code name}
         *                                  or {@code descriptor} is blank
         */
        public Extension {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(require, "require");
            Objects.requireNonNull(nulls, "nulls");
            Objects.requireNonNull(scope, "scope");
            if (className.isBlank() || receiver.isBlank() || name.isBlank() || descriptor.isBlank()) {
                throw new IllegalArgumentException(
                        "an extension needs a holder, a receiver, a name and a descriptor");
            }
        }

        /**
         * Creates an extension of the given kind with every policy at its default.
         *
         * <p>The defaults are {@link Require#REQUIRED}, {@link Nulls#UNCHECKED} and
         * {@link Scope#PUBLIC}, which are exactly the values {@link ManifestWriter} omits from
         * the document.
         *
         * @param className  the holder class declaring the extension, as a binary name
         * @param receiver   the type the extension appears on, as a binary name
         * @param name       the member's name as it is called
         * @param descriptor the JVM descriptor of the call site
         * @param kind       the shape of call this extension replaces
         * @throws NullPointerException     if any argument is {@code null}
         * @throws IllegalArgumentException if any of the four strings is blank
         */
        public Extension(@NotNull final String className,
                         @NotNull final String receiver,
                         @NotNull final String name,
                         @NotNull final String descriptor,
                         @NotNull final Kind kind) {
            this(className, receiver, name, descriptor, kind, Require.REQUIRED, Nulls.UNCHECKED,
                    Scope.PUBLIC);
        }

        /**
         * Reports whether a call to this extension needs a null check on its receiver.
         *
         * <p>True only for an instance extension asking for {@link Nulls#CHECKED}. The other two
         * kinds have no receiver value to check, and asking for a null policy on one is refused
         * at compile time as {@code AW1315}; agreeing with that here is what stops a hand-edited
         * manifest from asking for a check that could not be emitted.
         *
         * @return {@code true} when the kind is {@link Kind#INSTANCE} and the policy is
         *         {@link Nulls#CHECKED}
         */
        @Contract(pure = true)
        public boolean guarded() {
            // Only an instance extension has a receiver value to check. The processor refuses
            // Nulls on the other two kinds (AW1315), so this is a second opinion that agrees —
            // and one that a hand-edited manifest cannot get past.
            return this.kind == Kind.INSTANCE && this.nulls == Nulls.CHECKED;
        }

        /**
         * Creates an instance extension with every policy at its default.
         *
         * @param className  the holder class declaring the extension, as a binary name
         * @param receiver   the type the extension appears on, as a binary name
         * @param name       the member's name as it is called
         * @param descriptor the JVM descriptor of the call site, without the receiver
         * @throws NullPointerException     if any argument is {@code null}
         * @throws IllegalArgumentException if any of the four strings is blank
         */
        public Extension(@NotNull final String className,
                         @NotNull final String receiver,
                         @NotNull final String name,
                         @NotNull final String descriptor) {
            this(className, receiver, name, descriptor, Kind.INSTANCE);
        }

        /**
         * Returns the descriptor the holder's own member has, as opposed to the one written at
         * the call site.
         *
         * <p>For {@link Kind#INSTANCE} that is {@link #descriptor()} with {@link #receiver()}
         * inserted as the first parameter, because the receiver the call site leaves on the stack
         * is an ordinary argument of the implementation. For the other two kinds the two
         * descriptors are the same: a static call already passes what the implementation takes,
         * and a constant has no parameters, so its descriptor is its type.
         *
         * <p>For example, an instance extension on {@code java.lang.String} whose call site reads
         * {@code (I)Ljava/lang/String;} is implemented by a method of descriptor
         * {@code (Ljava/lang/String;I)Ljava/lang/String;}.
         *
         * @return the implementation's descriptor
         * @throws IllegalArgumentException if the kind is {@link Kind#INSTANCE} and
         *                                  {@link #descriptor()} is not a method descriptor, or
         *                                  {@link #receiver()} is not a binary class name —
         *                                  a name written with {@code /} rather than {@code .}
         *                                  is rejected here
         */
        @Contract(pure = true)
        @NotNull
        public String implementationDescriptor() {
            if (this.kind != Kind.INSTANCE) {
                // A static method's call site already passes what the implementation takes, and a
                // constant has no parameters at all: its descriptor is its type.
                return this.descriptor;
            }
            return MethodTypeDesc.ofDescriptor(this.descriptor)
                    .insertParameterTypes(0, ClassDesc.of(this.receiver))
                    .descriptorString();
        }

        /**
         * Returns {@link #receiver()} in the internal form the class file uses.
         *
         * <p>Every {@code .} becomes a {@code /}; nothing else is examined, so a name that is
         * already internal is returned unchanged.
         *
         * @return the receiver's internal name, such as {@code java/lang/String}
         */
        @Contract(pure = true)
        @NotNull
        public String receiverInternalName() {
            return this.receiver.replace('.', '/');
        }

        /**
         * Returns {@link #className()} in the internal form the class file uses.
         *
         * @return the holder class's internal name, such as {@code com/acme/Strings}
         */
        @Contract(pure = true)
        @NotNull
        public String classInternalName() {
            return this.className.replace('.', '/');
        }

        /**
         * The shape of call an extension replaces.
         *
         * <p>Each constant has a token, and the token — not the constant name — is what appears
         * in the {@code "kind"} field of a manifest. The token of {@link #INSTANCE} is never
         * written, since it is the value a reader assumes when the field is absent.
         *
         * @author Erik Pförtner
         * @since 0.1.0
         */
        public enum Kind {

            /**
             * A call on a receiver value, {@code receiver.name(...)}.
             *
             * <p>The receiver is not part of {@link Extension#descriptor()} and is the first
             * parameter of {@link Extension#implementationDescriptor()}. This is the only kind
             * for which {@link Extension#guarded()} can be true.
             */
            INSTANCE("instance"),

            /**
             * A call on the receiver type itself, {@code Receiver.name(...)}.
             *
             * <p>The call site passes exactly what the implementation takes, so the two
             * descriptors are the same.
             */
            STATIC("static"),

            /**
             * A read of a field on the receiver type, {@code Receiver.name}.
             *
             * <p>{@link Extension#descriptor()} is a field descriptor rather than a method
             * descriptor.
             */
            CONSTANT("constant");

            /** The text this kind is written as in the {@code "kind"} field of a manifest. */
            private final String token;

            /**
             * Binds a kind to the token it is written as.
             *
             * @param token the text this kind appears as in a manifest
             */
            Kind(@NotNull final String token) {
                this.token = token;
            }

            /**
             * Returns the kind written as the given token.
             *
             * <p>A {@code null} token reads as {@link #INSTANCE}: an entry naming no kind is an
             * instance extension, which is the only reading a manifest that omits the field can
             * have. Any other unrecognised text returns {@code null} rather than a guess, and a
             * reader that receives {@code null} is expected to drop the entry and report
             * {@code AW2300}, because rewriting a call site under the wrong shape is worse than
             * not rewriting it.
             *
             * @param token the token as written in the manifest, or {@code null} when the field
             *              is absent
             * @return the kind with that token, {@link #INSTANCE} when {@code token} is
             *         {@code null}, or {@code null} when no kind has that token
             */
            @Contract(pure = true)
            @Nullable
            public static Kind of(@Nullable final String token) {
                if (token == null) {
                    // A manifest that says nothing predates static extensions, and everything it
                    // holds is an instance extension. Defaulting is correct here and only here.
                    return INSTANCE;
                }
                for (final Kind kind : values()) {
                    if (kind.token.equals(token)) {
                        return kind;
                    }
                }
                return null;
            }

            /**
             * Returns the text this kind is written as in a manifest.
             *
             * @return the token, which is the constant's name in lower case
             */
            @Contract(pure = true)
            @NotNull
            public String token() {
                return this.token;
            }
        }
    }
}
