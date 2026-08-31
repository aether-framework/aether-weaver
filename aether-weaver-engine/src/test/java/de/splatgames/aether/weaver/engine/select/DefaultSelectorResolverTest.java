package de.splatgames.aether.weaver.engine.select;

import de.splatgames.aether.weaver.api.select.MemberKind;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSelectorResolverTest {

    @SuppressWarnings("unused")
    static class Fixture {
        int counter;
        String label;
        static long total;

        void close() {
        }

        String charge(java.math.BigDecimal amount) {
            return "";
        }

        String charge(java.math.BigDecimal amount, String currency) {
            return "";
        }

        int get() {
            return 0;
        }

        String process(String[] names, int times, List<String> extra) {
            return "";
        }

        private void secret() {
        }

        static void statics() {
        }
    }

    private static final DefaultSelectorResolver RESOLVER = new DefaultSelectorResolver();
    private static ResolutionContext context;

    @BeforeAll
    static void parseFixture() {
        final String resource = Fixture.class.getName().replace('.', '/') + ".class";
        try (var in = Objects.requireNonNull(
                DefaultSelectorResolverTest.class.getClassLoader().getResourceAsStream(resource))) {
            final ClassModel model = ClassFile.of().parse(in.readAllBytes());
            context = ResolutionContext.of(model, Map.of("BigDecimal",
                    ClassDesc.of("java.math.BigDecimal")));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("a fully qualified parameter type resolves to exactly one overload")
    void resolvesOneOverload() {
        final List<MemberRef> matches = RESOLVER.resolveAll(
                MemberSelector.parse("charge(java.math.BigDecimal)"), context);
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().methodType().parameterCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a simple name resolves through the import context")
    void resolvesThroughImports() {
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("charge(BigDecimal)"), context))
                .hasSize(1);
    }

    @Test
    @DisplayName("an unresolvable simple name matches nothing rather than everything")
    void unresolvableNameMatchesNothing() {
        // Treating an unknown name as a wildcard would silently bind the weave to an overload
        // the author never named.
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("charge(Unknown)"), context))
                .isEmpty();
    }

    @Test
    @DisplayName("an omitted parameter list matches every overload, which the caller must reject")
    void omittedParametersMatchAllOverloads() {
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("#charge"), context)).hasSize(2);
    }

    @Test
    @DisplayName("an empty parameter list matches only the no-argument member")
    void emptyParameterListIsExact() {
        final List<MemberRef> matches =
                RESOLVER.resolveAll(MemberSelector.parse("close()"), context);
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().methodType().parameterCount()).isZero();
    }

    @Test
    @DisplayName("a wildcard parameter matches one position of any type")
    void wildcardParameter() {
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("charge(*)"), context)).hasSize(1);
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("charge(*, *)"), context)).hasSize(1);
    }

    @Test
    @DisplayName("generics are matched after erasure")
    void genericsErased() {
        assertThat(RESOLVER.resolveAll(
                MemberSelector.parse("process(String[], int, java.util.List<String>)"), context))
                .hasSize(1);
    }

    @Test
    @DisplayName("a return type disambiguates")
    void returnTypeDisambiguates() {
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("get():int"), context)).hasSize(1);
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("get():java.lang.String"), context))
                .isEmpty();
    }

    @Test
    @DisplayName("the descriptor form resolves to the same member as the source form")
    void bothFormsResolveIdentically() {
        final List<MemberRef> viaSource =
                RESOLVER.resolveAll(MemberSelector.parse("charge(java.math.BigDecimal)"), context);
        final List<MemberRef> viaDescriptor = RESOLVER.resolveAll(
                MemberSelector.parse("desc:charge(Ljava/math/BigDecimal;)Ljava/lang/String;"),
                context);
        assertThat(viaDescriptor).isEqualTo(viaSource).hasSize(1);
    }

    @Test
    @DisplayName("fields resolve, with and without a type")
    void fields() {
        assertThat(RESOLVER.resolveAll(
                MemberSelector.parse("counter", MemberKind.FIELD), context)).hasSize(1);
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("counter:int"), context)).hasSize(1);
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("counter:long"), context)).isEmpty();
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("desc:label:Ljava/lang/String;"), context))
                .hasSize(1);
    }

    @Test
    @DisplayName("access flags are captured, because they decide the invocation opcode")
    void flagsAreCaptured() {
        final MemberRef secret =
                RESOLVER.resolveAll(MemberSelector.parse("secret()"), context).getFirst();
        assertThat(secret.isPrivate()).isTrue();
        assertThat(secret.isStatic()).isFalse();

        final MemberRef statics =
                RESOLVER.resolveAll(MemberSelector.parse("statics()"), context).getFirst();
        assertThat(statics.isStatic()).isTrue();

        final MemberRef total = RESOLVER.resolveAll(
                MemberSelector.parse("total", MemberKind.FIELD), context).getFirst();
        assertThat(total.isStatic()).isTrue();
    }

    @Test
    @DisplayName("a wildcard name with an omitted parameter list matches every method")
    void wildcardNameMatchesEveryMethod() {
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("*"), context))
                .hasSameSizeAs(RESOLVER.methods(context));
    }

    @Test
    @DisplayName("a wildcard parameter list still constrains arity")
    void wildcardParameterListConstrainsArity() {
        // "*(*)" is every method of arity one, not every method. Getting this wrong would make
        // an accidental "match everything" look like a precise selector.
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("*(*)"), context))
                .allSatisfy(m -> assertThat(m.methodType().parameterCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("a constant selector resolves to nothing: constants live in instructions")
    void constantsResolveToNothing() {
        assertThat(RESOLVER.resolveAll(MemberSelector.parse("int:42"), context)).isEmpty();
    }

    @Test
    @DisplayName("MemberRef renders as a descriptor selector that re-parses")
    void memberRefDescribesItself() {
        final MemberRef charge =
                RESOLVER.resolveAll(MemberSelector.parse("charge(java.math.BigDecimal)"), context)
                        .getFirst();
        assertThat(MemberSelector.parse(charge.describe())).isNotNull();
        assertThat(charge.describe()).startsWith("desc:").contains(".charge(");
    }

    @Test
    @DisplayName("the candidate listing puts the nearest name first and renders in the given form")
    void candidateListing() {
        final MemberSelector requested = MemberSelector.parse("charg(java.math.BigDecimal)");
        final List<String> lines = CandidateListing.describe(
                requested, RESOLVER.methods(context), MemberSelector.Form.SOURCE);

        assertThat(lines).isNotEmpty();
        assertThat(lines.getFirst())
                .as("a one-character typo must put the intended member first")
                .contains("charge(");
        assertThat(lines).allSatisfy(line ->
                assertThat(line).startsWith("available: ").doesNotContain("desc:"));

        final List<String> asDescriptors = CandidateListing.describe(
                requested, RESOLVER.methods(context), MemberSelector.Form.DESCRIPTOR);
        assertThat(asDescriptors).allSatisfy(line -> assertThat(line).contains("desc:"));
    }

    @Test
    @DisplayName("the listing is capped and reports the remainder")
    void listingIsCapped() {
        final List<MemberRef> many = new java.util.ArrayList<>();
        for (int i = 0; i < CandidateListing.MAX_ENTRIES + 5; i++) {
            many.add(MemberRef.ofMethod(ClassDesc.of("X"), "m" + i,
                    MethodTypeDesc.of(ConstantDescs.CD_void), java.util.Set.of(), false));
        }
        final List<String> lines = CandidateListing.describe(
                MemberSelector.parse("m()"), many, MemberSelector.Form.SOURCE);
        assertThat(lines).hasSize(CandidateListing.MAX_ENTRIES + 1);
        assertThat(lines.getLast()).isEqualTo("... and 5 more");
    }
}
