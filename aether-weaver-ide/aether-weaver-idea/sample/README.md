# Sample project — for trying the plugin by hand

Opened automatically by `./gradlew runIde`.

## The tour

Open the **target** files. Everything the plugin does is visible from the target's side; the weaves
are there to cause it.

| Open this | And you should see |
| --- | --- |
| `Gateway.java` | `@HEAD` with **two handlers in execution order**, `@RETURN` on every overload, `@INVOKE` on each call to `format` — twice on one line, counted `×2` rather than drawn twice |
| `Ledger.java` | `@FIELD` on a write only (`entries`) and on any access (`posted`), `@NEW` where a `Receipt` is made, `@THROW` before the exception leaves |
| `Router.java` | `@TAIL` on the single exit, `@INVOKE_AFTER` on the call to `lookup` |
| `Silent.java` | **nothing from the source alone**, three of four blocks once it is compiled — see below |
| `AuditWeave.java` | the weave side: Ctrl+B, Ctrl+Space, Shift+F6 and Alt+F7 inside the selector strings, and a **gutter icon on every handler** leading to what it weaves into |
| `ReceiptAccess.java` | **nothing greyed out** except the one declaration that deserves it — see below |
| `Reported.java` | **five underlines, and a quick fix** — see below |
| `DescriptorWeave.java` | the descriptor form, and `Alt+Enter` → *Convert selector to source form* |
| `Amounts.java` | **an extension class** — what `BigDecimal` gains, and where the code really lives |
| `src/test/java/.../AmountsInUse.java` | **the line the whole feature exists for** — `amount.asMoney("€")`, resolving, completing and navigating, on a JDK type nobody can edit — see below |
| `ReportedExtensions.java` | **thirteen underlines, four with a quick fix** — see below |
| `GeneratedShapes.java` | **what the generator writes** — an `@At` pinned by ordinal, a `@Slice` with both bounds, a `@Local` capture and a `@Redirect` mirroring the operation. Written by hand so that `compileSample` checks the shapes on every build |

### Navigation goes both ways

Open `Gateway.java` and `AuditWeave.java` side by side. Both files have gutter icons, and they are
not the same feature seen twice — they answer different questions for different people:

* on **`Gateway.charge`** — *what touches this method?* For somebody reading a target who does not
  know a weave exists. Hover it: the handlers are listed **in the order the engine runs them**, each
  with its priority. `PriorityWeave` (priority 100) comes before `AuditWeave` (priority 0), which is
  the opposite of alphabetical — the order is a statement about when code runs, not a sorted list.
* on **`AuditWeave.onCharge`** — *what does this handler touch?* For the author of the weave.
  `@Inject(method = "charge")` without a signature opens **all three** overloads, because that is what
  the selector names. The icon leads exactly where `Ctrl+B` leads, and it does so by using the same
  reference rather than working it out a second time.

### What to try on a block

* **Click the chevron** in the gutter beside a block — that block alone collapses to a strip, and the
  chevron turns. Everything else stays as it was.
* **Hover the chevron** — the tooltip says in words what the tag means: *runs on entry*, *replaces
  the call to format*, *runs once, on the way out*.
* **View → Show Injected Code** — collapses every block at once, everywhere.
* Note the **colour**: an injection is tinted like an added diff line, a redirect like a modified
  one. A redirect does not run beside the target's operation, it takes it out.

### `Silent.java` is the important file

`SilentWeave` declares four perfectly legal injections against it. Each one depends on something the
**source** does not have, and what the plugin draws now depends on whether it can read the
**compiled** target instead:

| Declaration | Source alone | With `Silent.class` built |
| --- | --- | --- |
| `@At(TAIL)` on a method with two returns | nothing — `TAIL` is the last return *in bytecode order*, which is not source order | still nothing, see below |
| `@At(CONSTANT, target = "int:42")` | nothing — a constant load is not a source literal; `"a" + "b"` folds to one, `2 * 3` is written nowhere | **drawn**, at the line the load was compiled from |
| `@At(INVOKE, ordinal = 0)` | nothing — an ordinal counts bytecode calls, and bytecode holds calls the source never shows | **drawn**, at the call the engine actually counts as first |
| `@At(INVOKE, shift = AFTER)` | nothing — a shift moves the site by instructions | **drawn**, at the line the shifted instruction belongs to |

So build the sample once and three of the four blocks appear. That is the point of the file: none of
them was ever *unknowable*, only unanswerable from the text, and the answer comes from handing the
declaration to the same resolver the build uses, against the same class file.

`TAIL` **on this method** stays silent either way, and deliberately. The engine can of course place
it — but the block would sit on whichever `return` the compiler emitted last, and a reader who moved
a `return` in the source would see the block jump for reasons the text does not explain. Compare
`Router.route`, where `TAIL` **is** drawn from the source alone: it has one exit, so "the last
return" is not a question. That contrast is the whole rule.

Delete `build/` and the three blocks go with it. Silence is what *unavailable* looks like, not what
*wrong* looks like — a drawn block does not read as an estimate, so there must never be one the
plugin cannot stand behind.

### `ReceiptAccess.java` is the other one to look at

A weave is a class nobody instantiates, holding handlers nobody calls and fields nobody assigns —
which is also a fair description of dead code. Without the plugin IntelliJ greys out the whole file:

| Declaration | What IntelliJ says on its own | Why that is wrong |
| --- | --- | --- |
| the weave class | *Class 'ReceiptAccess' is never used* | the weaver instantiates it |
| the `@Inject` handler | *Method 'onValid()' is never used* | the woven target calls it |
| its parameters | *Parameter '…' is never used* | the parameter list is the binding contract |
| `@Accessor` / `@Invoker` | *Method 'getAmount()' is never used* | never called *as written* — the generated method on the target is what callers reach |
| the `@Shadow` field | *Private field 'amount' is never assigned* | `Receipt` assigns it; the declaration is erased during weaving |

**Exactly one thing in that file should be grey: `reallyUnused()`.** Silencing everything a weave
declares would be easy and would cost the user the only warning they ever get about code that really
is dead — and an absent warning is not something anybody notices. So a `@Shadow` method nothing
calls, a `@Unique` member nothing uses and an ordinary unused private method all stay reported.

### `Reported.java` — thirteen underlines, all on code that compiles

That last part is the point. None of these is a Java mistake, so `javac` is content and the build
says nothing until the weaving step — or, for the misspelled selector, never: the injection simply
does not happen.

| Weave in the file | Code | What is wrong |
| --- | --- | --- |
| `NamesNoTarget` | `AW1001` | no target, so nothing would be woven — and it looks finished |
| `NamesTheTargetTwice` | `AW1002` | both forms at once; which one wins must not be a guess |
| `StaticWeaveReachingTooFar` | `AW1090` | `@Shadow` in a weave whose code never moves into the target |
| ″ | `AW1091` | `@Unique` where nothing is merged |
| `MisspelledSelector` | `AW1020` | `charg` — and this one has a **quick fix** |
| `PastedDescriptor` | `AW1017` | a `javap` descriptor with no `desc:` — **quick fix** |
| `HandlersThatCannotBeCalled` | `AW1040` | the handler takes a `String` where the target has a `BigDecimal` — **quick fix** |
| ″ | `AW1041` | the handler returns something; the injected call is a statement |
| ″ | `AW1071` | `ReturnableCallback<BigDecimal>` for a target returning `String` |
| `ConflictsWithTheTarget` | `AW1080` | a merged field the target already declares |
| ″ | `AW1031` | `@Shadow String posted` where the target has an `int` |
| `NamesEveryOverload` | `AW1021` | a bare `charge` against three overloads |
| `InstanceHandlerInAStaticWeave` | `AW1005` | no `this` exists for it — **quick fix** |

**Try the quick fix.** Caret on `charg`, `Alt+Enter`, *Change selector to 'charge'*. Then read the
result: `"charge(BigDecimal)"` — the signature survived. Replacing the whole string would have left
`"charge"`, which parses, resolves, and now names *all three* overloads instead of the one that was
narrowed to. A fix that quietly widens what somebody deliberately narrowed is worse than no fix,
because they accepted it and will not read it again.

**Then try to break it.** Change `charg` to `zzzzzzzz` and the offer disappears. A quick fix is
trusted more than a message is; offering the nearest name unconditionally would turn a typo into a
different *working* selector, binding the injection to a method nobody asked for.

The messages name the *reason*, not the rule — `AW1090` points at `@Accessor` rather than saying
"not allowed", because the reader's next question is how to reach the field, and `ReceiptAccess.java`
is the answer.

**Try the other quick fix.** Caret on `takesTheWrongType`, `Alt+Enter`, *Adjust handler parameters
to the target*. The type changes; the name you chose and the number of parameters you asked for do
not. A fix that replaced the list with the target's full signature would hand seven parameters back
to somebody who wanted two — the same mistake as widening a selector, and accepted just as readily.

**And the one that is not the plugin's own idea.** `PastedDescriptor` is a signature copied out of
`javap`. `Alt+Enter` offers the corrected string — but the plugin did not work out what was meant:
the framework's parser threw `AW1017` carrying the replacement, and the fix writes that. Recognising
descriptor shapes a second time inside the IDE would be a second opinion about the selector language,
and two opinions drift.

### Extensions — `Amounts.java`, then `AmountsInUse.java`

> The annotations live in `de.splatgames.aether.weaver.api.experimental` and are marked
> `@ApiStatus.Experimental`. Not because the feature is unfinished — it is tested end to end through
> a real `javac` — but because it is the one thing here that needs more of a build than a weaver:
> the `stubs` goal, a compiler argument, and this plugin if writing it is to be tolerable. The
> package name says so where somebody types the import, rather than in a paragraph they might not
> read.

Open them side by side. `Amounts.java` declares six methods and a constant on
`java.math.BigDecimal`; every call in `AmountsInUse.java` is written as though `BigDecimal` declared
them.

**Look at the top of `Amounts.java` first.** `@Extension(BigDecimal.class)` names the receiver
once, and `@Receiver` then appears on no parameter at all — except the two methods that declare a
`nulls` policy, because the policy lives on that annotation. Converting the class changed nothing at
any call site: `AmountsInUse.java` is the same file it was.

**Nothing there is red.** Without the plugin, every one of those lines reads *cannot resolve method*
— an error, on code a real build compiles and runs. That is the whole feature, and it is one line
long.

What to try:

* **`Ctrl+B`** on `asMoney` — lands in `Amounts.java`, on the `static` method that holds the code.
* **Type `amount.`** on a new line — `asMoney`, `isRefusable`, `orZero` and `split` are offered
  beside `BigDecimal`'s own members, because as far as resolution is concerned they *are* its
  members.
* **Look at `thirds`** — `split` returns `List<BigDecimal>`, not a raw `List`, so `part` is a
  `BigDecimal` and `asMoney` resolves on it. The generic signature survives the trip through the
  compile-time stub.
* **Type `BigDecimal.`** on a new line — `parse` is offered beside `valueOf` and `ZERO`. It is
  declared `@Receiver(BigDecimal.class)` on the *method*, which contributes a `static` member to the
  type rather than an instance member to its values. There is no receiver before the dot at all,
  only a class name, and the rewrite is smaller for it: the call already passes exactly what the
  implementation takes, so only the owner of the `invokestatic` changes.
* **Look at `charged`** — `plus(fee, tax)` is written as two arguments rather than as an array. The
  stub carries the `varargs` flag along with the descriptor, and it carries the `throws` clause too:
  an extension declaring a checked exception cannot be called without handling it, which is the one
  way this feature could otherwise have made a program worse than the handwritten static call.
* **The gutter beside each method in `Amounts.java`** names the type it is contributed to — the one
  fact the declaration states least visibly, in a parameter annotation halfway along a signature.
  Click it to open the receiver.
* **`Alt+F7` on `asMoney`** finds `AmountsInUse.line`. A call written on a `BigDecimal` is a usage
  of a method declared in `Amounts`, however little the two lines look alike; an IDE reporting *no
  usages* there is telling you it is safe to delete.
* **The Weaves tool window** (right-hand side) lists it under **Extensions**, with every method and
  the type it lands on — including extensions that come from a dependency, whose declarations are
  in a jar and mentioned nowhere in your source.
* **Type `amount.` and read the list.** The contributed methods sit among `BigDecimal`'s own, each
  marked *extension in Amounts* — right where they resolve, and honest about not being declared by
  the class in front of them.
* **`BigDecimal.CENT`** in `andACent` is a *constant* `BigDecimal` never declared — a field read,
  not a call, repointed at `Amounts` by the same rewrite one instruction smaller.
* **Write a call that does not exist yet** — say `amount.rounded()` — and press `Alt+Enter`.
  *Create extension method 'rounded' in Amounts* writes it into `Amounts.java` with the receiver in
  front and the argument types read off the call. Java's own *Create method* offers to add it to
  `BigDecimal`, which is a class file in the JDK and cannot be added to; this is the offer that can.
  It appears only when exactly one extension class already contributes to that type — where a *new*
  holder should go is a decision about your layout, not one an intention should make for you.

**Nothing is added to `BigDecimal`.** It could not be: it is loaded long before any weaver exists.
The method stays in `Amounts`, and the *call site* is what the weaver rewrites —
`amount.asMoney("€")` becomes `Amounts.asMoney(amount, "€")`, an `invokestatic` costing exactly what
writing the static call by hand would have cost. It is the same answer Kotlin arrived at, for the
same reason.

**Why the caller sits in `src/test/java`.** An extension has to be compiled *before* the code that
calls it, because the compile-time stub is derived from the extension's own class file. A caller can
never sit in the same compilation as its extension, and main-then-test is the smallest arrangement
in which one module can do both. In a real project the extensions are a module, or a jar, that the
calling code depends on — exactly where Kotlin's own extensions live.

**And why nothing here compiles it.** `javac` cannot resolve `amount.asMoney("€")` unaided, and no
bytecode transformation can change what `javac` accepts; a real build hands it a stub and rewrites
the calls afterwards. The header of `AmountsInUse.java` carries the two goals and the one compiler
argument that do it. This sample runs neither, for the same reason its POM sets `<proc>none</proc>`:
everything above is a statement about the **IDE**, and every one of them holds with no build step at
all. The build is what makes it run; the plugin is what makes it readable while you write it.

### `ReportedExtensions.java` — thirteen underlines, four with a quick fix

The same discipline as `Reported.java`, and the timing is what makes it sharper. A weave that is
wrong is caught when the weaver runs, and only the weave has to change. An **extension is validated
once**: by the time anything is woven, `javac` has already compiled every caller against a stub built
from whatever was accepted. A declaration that turns out to be wrong is wrong in code that already
exists.

So the editor is not saving a few seconds here. It is the difference between an edit and a migration.

| Class | Code | `Alt+Enter` |
| --- | --- | --- |
| `NotFinal` | `AW1300` | *Declare the extension class final* |
| `InstanceContribution` | `AW1301` | *Declare the contributed method static* |
| `NoReceiver` | `AW1302` | *Mark the first parameter @Receiver* |
| `ReceiverLate` | `AW1303` | *Move the @Receiver parameter first* |
| `PrimitiveReceiver` | `AW1304` | — |
| `CollidesWithBigDecimal` | `AW1305` | — |
| `GenericHolder` | `AW1306` | — |
| `HasASupertype` | `AW1307` | — |
| `GenericContribution` | `AW1310` | — |
| `ParameterisedReceiver` | `AW1311` | — |
| `ObjectReceiver` | `AW1312` *(warning)* | — |
| `ReceiverNamedTwice` | `AW1313` | — |
| `ClassLevelMismatch` | `AW1316` | — |

**The nine without a fix are the interesting half.** What a primitive receiver should become, what
to rename a method that collides with a real one, how a generic extension should be narrowed — each
is a design decision, and a menu entry guessing at it would produce compiling source that means
something nobody chose.

`CollidesWithBigDecimal` is the one to read twice. `BigDecimal.abs()` is real, so `javac` resolves
the call to it and the extension is dead code — **an extension cannot override, intercept or shadow
anything.** To change what an existing method does, `AuditWeave.java` is the shape to copy.

`ParameterisedReceiver` is the one that looks like it should work. `@Receiver List<BigDecimal>`
erases to `java.util.List`, so it would be contributed to *every* `List` in the program, and a
`List<Integer>` would call it and hand its elements to code expecting amounts.

### Four intentions, on `Alt+Enter`

| Where | Offer | Not offered when |
| --- | --- | --- |
| a `desc:` selector | *Convert selector to source form* | the round trip would not survive |
| `"charge(BigDecimal)"` in `AuditWeave` | *Qualify the selector's parameter types* | already qualified, or the name has no parameter list at all |
| an unresolved `this.something` in a weave | *Declare @Shadow for the target's member* | the target has no such member; the weave is `static` |
| any ordinary class, e.g. `Gateway` | *Create a weave for this class* | it is already a weave, or an interface |

*Qualify* does not expand imports — that would be name resolution implemented twice, disagreeing with
the compiler exactly where it is interesting. It resolves the selector to the one method it names and
writes that method's own types, then resolves the result again to check it still lands on the same
method. A bare `"charge"` is left alone: turning it into a signature narrows a selector that
deliberately named every overload, and that is the author's decision.

*Declare @Shadow* drops `final`. A shadowed field has no initialiser and a weave may not declare a
constructor (`AW1081`), so a `final` copy would never be definitely assigned.

`StaticWeaveReachingTooFar` has the fix for the other direction: `Alt+Enter` on its `@Shadow` gives
*Replace @Shadow with a generated member* — the accessor pair the `AW1090` message already points at.
It rewrites the declaration and deliberately not the use sites, which go red immediately; rewriting
them means knowing which target instance is in scope at each one, and guessing wrong produces code
that compiles and reads the wrong object.

### `Alt+Insert` → *Weave Handler…*

The other side of the same knowledge. Put the caret in any weave — `AuditWeave` will do — and
generate a handler for a target method. The selector arrives as the full signature rather than the
bare name (a bare name is correct right up to the day somebody overloads the target, at which point
a file nobody touched becomes `AW1021`), the parameters are the target's, and a non-`void` target
brings the `ReturnableCallback` that lets the handler change the outcome.

In a **static** weave the same action writes something different: `public static`, and the target
instance as the first parameter. Not decoration — a static weave is never merged, so the emitted
call is an ordinary cross-class invocation, and a `private` handler is `AW1042` discovered at the
first execution of the injected call rather than at build time.

The dialog is laid out the way the platform lays out *Create Test*: a labelled form, the target
methods as a checkbox table underneath, and a live preview of the handler that is about to be
written. It remembers every choice except the group.

| Option | Values | Notes |
| --- | --- | --- |
| **Handler** | Inject, Redirect | *Redirect* replaces the operation instead of running beside it, and is offered only at `INVOKE`, `FIELD` and `NEW` — at a position there is nothing to replace, which the engine calls `AW1061` |
| **Inject at** | Head, Return, Tail, Invoke, Invoke after, Field, New, Constant | the last five name an operation *inside* the method and are read out of the compiled target — see below |
| **Positions** | every / every, or fail the build / exactly one / first only | one choice instead of `ordinal`, `require` and `allow`. *Fail the build* is `require = 1`: an injection that matches nothing is otherwise a warning-free no-op, and the day the target changes shape becomes a silent behaviour change instead of a build failure. *First only* is offered for **Return** alone, because there is one head and one tail |
| **Selector** | source qualified, source simple names, descriptor | all three legal, none second-class |
| **Visibility** | automatic, `public`, `protected`, package-private, `private` | *automatic* is `private` in an instance weave and `public` in a static one. A static weave is offered only *automatic* and `public` — its handler is called across classes, and the target's package is not the weave's to choose |
| **Group** | the groups the weave declares | **the row is absent when it declares none.** A group name nobody declared is not an error: the engine sums it into a total no `@Group` ever reads, and a grouped injection is deliberately exempt from its own `require` — so a misspelt group is *silently weaker* than writing no group at all. Nothing may offer one from free text |
| **Name prefix** | text, default `on` | empty names the handler after the target method |
| **Take a callback parameter** | on / off | `ReturnableCallback<R>` for a target that returns something, plain `Callback` for a `void` one — cancelling is the reason to take one, and a `void` method cancels perfectly well |
| **Capture the locals in scope** | on / off | adds an `@Local(name = "…")` parameter per local variable the target has in scope — see below |
| **Slice from** / **Slice to** | the calls of the enclosing method | confines the search to the region between two calls — see below |
| **Generate a documentation comment** | on / off | a skeleton with a `@param` per parameter, summary line left to be replaced |
| **Mark the body with a TODO** | on / off | |

`static` is not on that list and never will be: an instance handler in a static weave is `AW1005`,
and the weave kind already decides it.

**Names are checked against the target, not only against the weave.** An instance weave's handler
is *merged into* the target, so a handler whose name and parameter types match one the target already
declares is `AW1080` — and uniquely among merge collisions, one `@Unique` cannot repair, because the
injected call sites name the handler. The engine says exactly that in the diagnostic's remedy. The
generator numbers instead.

### Operation points and `@Redirect`, read out of the compiled target

`INVOKE`, `INVOKE_AFTER`, `FIELD` and `NEW` all need `@At(target = …)` naming an operation *inside*
the chosen method, and the engine matches those against the **instruction stream**. That stream is
not the source. It holds calls nobody wrote — a boxing `valueOf`, a for-each's `iterator`, a bridge —
and ordinals are counted in bytecode order, which source order does not promise. So these points are
read from the same artefact the engine will read: the compiled class.

Pick one of them under **Inject at**, and the list below turns into the operations found inside the
target's methods. Pick **Redirect** under **Handler**, and the generated method replaces the
operation instead of running beside it — with the signature the operation requires, receiver first.

| What you chose | What is written |
| --- | --- |
| Inject at a call | `@Inject(method = "…", at = @At(value = Point.INVOKE, target = "Buffer.add(Item)", ordinal = 1))`, with the **enclosing** method's parameters — the point moved, the handler did not |
| Redirect a call | `@Redirect(…)` and `Item onAdd(Buffer buffer, Item item)` — the receiver, then the arguments, returning what the call returned |
| Inject at a constant | `target = "string:\"retry\""` — rendered by the API's own `ConstantSelector`, never formatted by the plugin |

**Nothing in the plugin decides what matches.** For each instruction it *proposes* an `@At` and asks
the engine's own `PointResolver`, over the engine's own `BuiltInPoints`, what that proposal selects;
an operation is offered only when the answer is exactly one position and it is the instruction the
proposal was built from. The redirect signature comes from `RedirectShapes`, which is
`RedirectedOperation` — the injector's own — with one fact made public. A test hands the generated
handler back to `RedirectShapes.accepts`, the predicate `RedirectInjector` will apply at build time.

**The ordinal is always written**, because you picked a row. Without one the annotation means "every
call to that member", which is a legitimate thing to want and is not what choosing one line says.

**A stale class file is treated as no class file.** Edit the target and the list says so and offers
nothing until you rebuild. Offering positions from the previous compilation is precisely the failure
this approach exists to avoid; being unhelpful is recoverable, being confidently wrong is not.

**`CONSTANT` needed an engine fix before it could be offered at all.** `ConstantPoint` compared the
target's text, minus everything up to its first colon, against `String.valueOf(value)`. That works
for a number and for nothing else: the framework's own `ConstantSelector.render` produces
`string:"retry"`, and stripping the prefix off that leaves `"retry"` *with its quotes* against a
value of `retry` without them. A class constant was compared against `ClassOrInterfaceDesc[Void]`,
and `null` against a `DynamicConstantDesc`. **Three of the grammar's seven kinds could never match
anything** — including the one people reach for first. The point now compares the parsed
`ConstantDesc` it was already being handed, and the text comparison stays as the fallback so that
`string:retry` — a *field* selector by the grammar's own disambiguation rule — keeps working.

The engine's existing constant tests did not catch this because they build the declaration from raw
text, which is not the path `WeaveClassParser` takes. `PointResolverTest` now has a `parsed(...)`
helper that builds it the way the parser does.

### `@Local`, once the objection stopped being true

`@Local` captures a variable by the name the compiler recorded, and that name exists only when the
target was compiled with a local variable table. While the plugin could only read source, that was a
property of somebody else's build and unknowable — so generating one meant generating `AW1052` on a
project the plugin could not inspect, and it was left out for exactly that reason.

Reading the compiled target turns it into a lookup. `LocalTable.isAvailable()` **is** the `AW1052`
question, and `namesLiveAt` is the other one. Tick *Capture the locals in scope* and each local the
target has in scope arrives as `@Local(name = "first") Item first`, with the type the table records.

Two rules, and both matter:

* **Live at every site, not at some.** `RETURN` matches every return, and a variable in scope at one
  need not be in scope at another — a capture that resolves at one site and not the next is `AW1050`
  on a handler that looked fine. A local is offered only when it is live at *every* site the
  injection resolved to, which is why `HEAD` offers none at all.
* **The method's own parameters are not offered.** They are in the table like everything else, but
  the handler already receives them: its parameter list is a prefix of the target's. Offering them
  would be offering the same value twice under two mechanisms.

Position in the parameter list is free — both the processor and this plugin skip a `@Local`
parameter when checking the prefix rule — so they go after the target's own arguments and before the
callback, which is where they read best.

### `@Slice`, bounded by two calls you pick

`@At`'s own documentation makes the argument: "the first `flush` after `begin`" keeps working when
somebody adds a `flush` earlier in the method, and "the third `flush`" does not. That is the whole
value of a slice — and which region is the interesting one is a statement about *intent*, so the
bounds are always yours. **Slice from** and **Slice to** offer the calls of the method the
highlighted row sits in; leave either at *(whole method)* and no slice is written.

**The ordinal inside a slice is not the ordinal in the method.** The engine narrows the body first
and applies the ordinal second, so the same instruction has one number against the method and another
inside the region. The operation list is therefore enumerated *with the slice in effect*, and the
number you see is the number the engine will count. A generator that computed the ordinal against the
whole method and then wrote a slice beside it would bind the injection to a different call, and the
result would compile and run and be wrong. The sample fixture is built to catch exactly that: two
`add` calls with a landmark between them, so the second is ordinal 1 in the method and ordinal 0 in
the second slice.

Both bounds are pinned with their own ordinals, because `SliceSpec` refuses a bound that resolves to
several positions — a bound that matched twice would not bound anything. The slice is written
unnamed and the `@At` carries no `slice = "…"` reference: `SliceSpec.matches` compares the point's
reference with the slice's id, and a point that names none carries `""`. An id would have to be
written twice to say what an empty one says once.

Bounds must be *both* set and must not cross; the dialog says so rather than generating `AW1122`.

**Whatever spelling is chosen, it is resolved back before it is written.** If the descriptor this
plugin encoded is not the one the framework's parser reads, or if the simple-name form turns out to
be ambiguous, the generator falls back to the qualified source form rather than putting it in your
file. That guard is not theoretical: the first implementation reached for
`ClassUtil.getVMParametersMethodSignature`, whose name promises a method signature and which actually
returns a debugger's comma-separated parameter list. A counter-probe restoring that mistake fails the
positive descriptor test and leaves the round-trip test green — because the fallback did its job.

Generator and inspection read one model of the rule, so what the one writes the other accepts. A
test asserts exactly that, for every method of the target at once.

**The inspection that changed the sample.** `AuditWeave` — in the part of this project that is
meant to be *correct* — used to read `method = "charge"`, on the belief that a bare name deliberately
names every overload. The grammar allows it and the build does not: `Gateway` has three, and
`Inject#method()` says the selector "becomes ambiguous and is reported (`AW1021`) rather than
resolved arbitrarily". The sample was teaching the mistake, and nothing noticed until the inspection
existed. `NamesEveryOverload` now keeps the case where it belongs.

**What is deliberately not reported here:** anything needing a compiled body (`AW1043`, `AW1050`).
The build reports those, and the annotation processor says out loud (`AW1200`) when it cannot. An
editor approximating them would produce false underlines on exactly the shapes injection exists for
— and two of those are enough for somebody to switch the inspection off, taking the true reports
with it. `AW1060`, two `@Redirect`s on one call site, is out for the same reason: deciding it means
knowing which bytecode operations each `@At` matched, and two redirects that look identical in source
can land on different sites once ordinals and slices are applied.

### The *Weaves* tool window

On the right, under **Weaves**. Every weave in the project, what it targets, and its handlers in the
order the engine will run them — with `instance` or `static`, the priority where one is declared, and
whether each selector binds. Double-click a row to open the declaration.

**Execution order is the reason to open it.** Everything else it lists can be read off the source;
the order two handlers run in cannot. It is decided by priority first and by names afterwards, and
source order has nothing to do with it — `AuditWeave` and `PriorityWeave` both inject at the head of
`Gateway.charge(BigDecimal)`, and neither file mentions the other.

**It shows the plan, and says so in the footer.** The framework's `explain` report has a second
half — which bytecode positions each injection point actually matched — and that is the build's to
produce. The plugin has no compiled bytes for the module being edited, and a count computed from
source would be a guess presented as a measurement. The window states the boundary rather than
leaving a column mysteriously empty.

## It is an ordinary Maven project, on purpose

A `pom.xml` depending on `de.splatgames.aether.weaver:aether-weaver-api`, standalone — no parent, and
the reactor does not list it. It stands in for a *user's* project and resolves the API from the
repository the way a user's project does.

That matters for what the sample can prove. Anything hand-assembled — a stub of the annotations, a
hand-written `.iml` naming a jar — agrees with the plugin by construction, because the same person
wrote both. The published jar can disagree, and that disagreement is what this project exists to
surface. Not hypothetical: the first draft stubbed `@Inject(at = "HEAD")` as a `String`, and the real
API declares `At[] at()`.

## Two guards, because two things can rot

`compileSample` compiles this source against the resolved API on every `check`: real usage that
nobody compiles decays into wishful usage.

`checkSampleVersion` fails if `pom.xml` and the plugin's `aetherWeaverVersion` disagree. A standalone
project declares the version twice, and that drift is silent — the sample would resolve a different
API than the plugin was built against, and the mismatch would read as the plugin misbehaving.

### Prerequisite

`mvn install` at the repository root, so the API artefact is in the local repository. The plugin
build needs it anyway.

## The other counter-probe

`NotAWeave.java` carries the same `method = "charge"` attribute with no enclosing `@Weave`.
Navigation and completion must do **nothing** there. If they fire, the plugin is claiming annotations
that belong to somebody else.

`TargetsByName.java` names its target as a string rather than a class literal. Both forms are legal
and both must work, or the string form quietly becomes second-class.
