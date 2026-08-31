package com.acme.payments;

import java.math.BigDecimal;
import java.util.List;

/*
 * THIS IS THE FILE THE WHOLE FEATURE EXISTS FOR.
 *
 * Every call below is written as though java.math.BigDecimal declared the method. It does not, and
 * it never will — BigDecimal is loaded long before any weaver exists. The method lives in
 * Amounts.java and the CALL SITE is what the weaver rewrites.
 *
 * What to look at:
 *
 *   * Nothing is red. Without the plugin, every one of these lines is "cannot resolve method" — an
 *     error, on code the build compiles and runs.
 *   * Ctrl+B on `asMoney` lands in Amounts.java, on the static method that holds the code.
 *   * Type `amount.` on a new line and read the completion list: asMoney, isRefusable, orZero and
 *     split are offered beside BigDecimal's own members, because as far as resolution is concerned
 *     they are its members.
 *   * `split` returns List<BigDecimal>, not a raw List. The generic signature survives the trip
 *     through the compile-time stub.
 *   * `BigDecimal.parse("12.00")` — a STATIC method on a type nobody here can edit. Type
 *     `BigDecimal.` on a new line and it is in the completion list beside valueOf and ZERO.
 *   * `plus(fee, tax)` is written as two arguments rather than as an array, because the stub
 *     carries the varargs flag as well as the descriptor.
 *
 * WHY THIS FILE IS NOT COMPILED BY ANYTHING HERE
 *
 * javac cannot resolve `amount.asMoney("€")` on its own, and no bytecode transformation can change
 * what javac accepts. A real build hands the compiler a stub — BigDecimal's own class file with
 * these signatures added — and then rewrites the calls afterwards:
 *
 *     <plugin>
 *       <groupId>de.splatgames.aether.weaver</groupId>
 *       <artifactId>aether-weaver-maven-plugin</artifactId>
 *       <executions>
 *         <execution><id>stubs</id><phase>process-classes</phase>
 *           <goals><goal>stubs</goal></goals></execution>
 *         <execution><id>weave</id><goals><goal>weave-tests</goal></goals></execution>
 *       </executions>
 *     </plugin>
 *
 *     <!-- maven-compiler-plugin -->
 *     <testCompilerArgument>--patch-module</testCompilerArgument>
 *     ... java.base=${project.build.directory}/aether-weaver/stubs/patch/java.base
 *
 * This sample deliberately runs neither: its pom sets <proc>none</proc> because the project exists
 * to be READ in the IDE, not built. Everything above is a statement about the IDE, and every one of
 * them holds with no build step at all — which is the point. The build is what makes it run; the
 * plugin is what makes it readable while you write it.
 *
 * And note where this file is: src/test/java, not src/main/java. An extension has to be compiled
 * before the code that calls it, because the stub is derived from the extension's own class file —
 * so a caller can never sit in the same compilation as its extension. Main-then-test is the
 * smallest arrangement in which one module can do both.
 */

final class AmountsInUse {

    private AmountsInUse() {
        throw new AssertionError("no instances");
    }

    static String line(final BigDecimal amount) {
        return amount.asMoney("€");
    }

    static boolean refused(final BigDecimal amount) {
        // orZero is deliberately null-tolerant; isRefusable is deliberately not the same question.
        return amount.orZero().signum() == 0 || amount.isRefusable();
    }

    static String charged(final String text, final BigDecimal fee, final BigDecimal tax) {
        return BigDecimal.parse(text).plus(fee, tax).asMoney("€");
    }

    static BigDecimal andACent(final BigDecimal amount) {
        return amount.plus(BigDecimal.CENT);
    }

    static List<String> thirds(final BigDecimal amount) {
        // split() returns List<BigDecimal>, so `part` is a BigDecimal and asMoney resolves on it.
        // Had the stub lost the generic signature, `part` would be Object and this line would not
        // compile — which is how the signature-carrying half of the feature makes itself visible.
        return amount.split(3).stream().map(part -> part.asMoney("€")).toList();
    }
}
