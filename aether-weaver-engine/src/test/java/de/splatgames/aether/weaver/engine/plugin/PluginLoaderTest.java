package de.splatgames.aether.weaver.engine.plugin;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.spi.Alias;
import de.splatgames.aether.weaver.api.spi.ConfigView;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.api.spi.InjectionPoint;
import de.splatgames.aether.weaver.api.spi.InjectionPointFactory;
import de.splatgames.aether.weaver.api.spi.Injector;
import de.splatgames.aether.weaver.api.spi.InjectorFactory;
import de.splatgames.aether.weaver.api.spi.PluginContext;
import de.splatgames.aether.weaver.api.spi.PluginEvent;
import de.splatgames.aether.weaver.api.spi.PluginId;
import de.splatgames.aether.weaver.api.spi.WeaverApi;
import de.splatgames.aether.weaver.api.spi.WeaverPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class PluginLoaderTest {

    private final List<Diagnostic> reported = new ArrayList<>();

    private final DiagnosticListener listener = this.reported::add;

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("plugins are sorted by namespace, never by registration order")
        void sortedByNamespace() {
            final PluginRegistry registry = load(
                    plugin("zebra", ctx -> { }),
                    plugin("acme", ctx -> { }),
                    plugin("middle", ctx -> { }));

            assertThat(registry.plugins()).extracting(PluginId::namespace)
                    .as("ServiceLoader order follows classpath order and file-system enumeration; "
                            + "relying on it would make two identical builds disagree, and the "
                            + "fingerprint would record the difference in every woven class")
                    .containsExactly("acme", "middle", "zebra");
        }

        @Test
        @DisplayName("the same inputs in a different order produce the same registry")
        void orderOfInputsDoesNotMatter() {
            final List<String> forwards = load(
                    plugin("acme", ctx -> { }), plugin("beta", ctx -> { }))
                    .plugins().stream().map(PluginId::namespace).toList();
            reported.clear();
            final List<String> backwards = load(
                    plugin("beta", ctx -> { }), plugin("acme", ctx -> { }))
                    .plugins().stream().map(PluginId::namespace).toList();

            assertThat(backwards).isEqualTo(forwards);
        }
    }

    @Nested
    @DisplayName("the version gate")
    class VersionGate {

        @Test
        @DisplayName("a plugin from the future is refused with AW3112, naming both levels")
        void tooNewIsRefused() {
            final PluginRegistry registry = load(
                    plugin("acme", WeaverApi.LEVEL + 1, ctx -> { }));

            assertThat(registry.isEmpty()).isTrue();
            assertThat(codes()).containsExactly("AW3112");
            assertThat(reported.getFirst().details())
                    .anySatisfy(d -> assertThat(d).contains(String.valueOf(WeaverApi.LEVEL + 1)))
                    .anySatisfy(d -> assertThat(d).contains(String.valueOf(WeaverApi.LEVEL)));
        }

        @Test
        @DisplayName("a plugin from before the supported range is refused with AW3113")
        void tooOldIsRefused() {
            final PluginRegistry registry = load(
                    plugin("acme", PluginLoader.MINIMUM_SUPPORTED_API_LEVEL - 1, ctx -> { }));

            assertThat(registry.isEmpty()).isTrue();
            assertThat(codes()).containsExactly("AW3113");
        }

        @Test
        @DisplayName("refusal happens before contribute is ever called")
        void refusedPluginNeverContributes() {
            final boolean[] contributed = {false};
            load(plugin("acme", WeaverApi.LEVEL + 1, ctx -> contributed[0] = true));

            assertThat(contributed[0])
                    .as("the whole point of the gate is that an incompatible plugin never runs; "
                            + "letting it contribute first would be the LinkageError-inside-"
                            + "class-loading failure the gate exists to prevent")
                    .isFalse();
        }

        @Test
        @DisplayName("a compatible plugin passes")
        void currentLevelPasses() {
            assertThat(load(plugin("acme", ctx -> { })).plugins()).hasSize(1);
            assertThat(reported).isEmpty();
        }
    }

    @Nested
    @DisplayName("namespace ownership")
    class Namespaces {

        @Test
        @DisplayName("two plugins claiming one namespace is AW3111, naming both")
        void collisionNamesBoth() {
            final PluginRegistry registry = load(
                    plugin("acme", "First", "1.0", WeaverApi.LEVEL, ctx -> { }),
                    plugin("acme", "Second", "2.0", WeaverApi.LEVEL, ctx -> { }));

            assertThat(registry.plugins()).hasSize(1);
            assertThat(codes()).containsExactly("AW3111");
            assertThat(reported.getFirst().details())
                    .anySatisfy(d -> assertThat(d).contains("First"))
                    .anySatisfy(d -> assertThat(d).contains("Second"));
        }

        @Test
        @DisplayName("a contribution outside the plugin's namespace is dropped with AW3110")
        void contributionOutsideNamespaceIsDropped() {
            final PluginRegistry registry = load(plugin("acme", ctx ->
                    ctx.points(pointFactory("acme", Set.of("other:THING")))));

            assertThat(codes()).containsExactly("AW3110");
            assertThat(registry.points().size())
                    .as("the offending contribution is dropped; the plugin itself still loads")
                    .isZero();
            assertThat(registry.plugins()).hasSize(1);
        }

        @Test
        @DisplayName("a factory declaring a foreign namespace is dropped with AW3110")
        void foreignFactoryNamespaceIsDropped() {
            load(plugin("acme", ctx -> ctx.points(pointFactory("other", Set.of("other:THING")))));

            assertThat(codes()).containsExactly("AW3110");
            assertThat(reported.getFirst().remedy())
                    .hasValueSatisfying(r -> assertThat(r).contains("acme"));
        }

        @Test
        @DisplayName("one bad contribution does not lose the plugin's good ones")
        void goodContributionsSurvive() {
            final PluginRegistry registry = load(plugin("acme", ctx -> ctx
                    .points(pointFactory("acme", Set.of("wrong:A")))
                    .points(pointFactory("acme", Set.of("acme:B")))));

            assertThat(codes()).containsExactly("AW3110");
            assertThat(registry.points().ids()).containsExactly("acme:B");
        }
    }

    @Nested
    @DisplayName("failure isolation")
    class Isolation {

        @Test
        @DisplayName("a plugin that throws while contributing is skipped, the others load")
        void contributeFailureIsContained() {
            final PluginRegistry registry = load(
                    plugin("broken", ctx -> {
                        throw new IllegalStateException("boom");
                    }),
                    plugin("healthy", ctx -> ctx.points(pointFactory("healthy",
                            Set.of("healthy:X")))));

            assertThat(codes()).containsExactly("AW3115");
            assertThat(registry.plugins()).extracting(PluginId::namespace)
                    .containsExactly("healthy");
            assertThat(registry.points().ids()).containsExactly("healthy:X");
        }

        @Test
        @DisplayName("a factory whose kinds() throws is contained, and the others still load")
        void factoryReadFailureIsContained() {
            // contribute() returning is not the plugin stopping: kinds(), ids() and aliases()
            // are its code too, and the loader used to read them after the guard had returned.
            final InjectorFactory broken = new InjectorFactory() {
                @Override
                public String namespace() {
                    return "broken";
                }

                @Override
                public Set<InjectorKind> kinds() {
                    throw new IllegalStateException("boom");
                }

                @Override
                public Injector create(final InjectorKind kind) {
                    throw new UnsupportedOperationException("not reached");
                }
            };

            final PluginRegistry registry = load(
                    plugin("broken", ctx -> ctx.injectors(broken)),
                    plugin("healthy", ctx -> ctx.points(pointFactory("healthy",
                            Set.of("healthy:X")))));

            assertThat(codes()).containsExactly("AW3115");
            assertThat(registry.plugins()).extracting(PluginId::namespace)
                    .as("the plugin that threw contributes nothing, and the loader carries on")
                    .containsExactly("healthy");
            assertThat(registry.points().ids()).containsExactly("healthy:X");
        }

        @Test
        @DisplayName("a plugin whose id() throws is refused without taking the loader down")
        void identityFailureIsContained() {
            final WeaverPlugin broken = new WeaverPlugin() {
                @Override
                public PluginId id() {
                    throw new IllegalStateException("no identity");
                }

                @Override
                public int apiLevel() {
                    return WeaverApi.LEVEL;
                }
            };

            final PluginRegistry registry = PluginLoader.load(List.of(broken),
                    PluginLoader.acceptAll(), id -> ConfigView.empty(), listener);

            assertThat(registry.isEmpty()).isTrue();
            assertThat(codes()).containsExactly("AW3114");
        }

        @Test
        @DisplayName("an observer that throws is a warning and the rest still hear the event")
        void observerFailureDoesNotStopDelivery() {
            final List<String> heard = new ArrayList<>();
            final PluginRegistry registry = load(
                    observer("aaa", event -> {
                        throw new IllegalStateException("boom");
                    }),
                    observer("bbb", event -> heard.add("bbb")));

            registry.publish(new PluginEvent.PluginsLoaded(registry.plugins()), listener);

            assertThat(codes()).containsExactly("AW3118");
            assertThat(heard)
                    .as("one broken observer must not silence the others")
                    .containsExactly("bbb");
        }
    }

    @Nested
    @DisplayName("the allowlist")
    class Permission {

        @Test
        @DisplayName("a plugin outside the allowlist is refused with AW3119")
        void notPermittedIsRefused() {
            final PluginRegistry registry = PluginLoader.load(
                    List.of(plugin("acme", ctx -> { })),
                    id -> "trusted".equals(id.namespace()),
                    id -> ConfigView.empty(), listener);

            assertThat(registry.isEmpty()).isTrue();
            assertThat(codes()).containsExactly("AW3119");
            assertThat(reported.getFirst().remedy())
                    .hasValueSatisfying(r -> assertThat(r).contains("aether.weaver.plugins.allow"));
        }

        @Test
        @DisplayName("a refused plugin never contributes")
        void refusedPluginNeverContributes() {
            final boolean[] contributed = {false};
            PluginLoader.load(List.of(plugin("acme", ctx -> contributed[0] = true)),
                    id -> false, id -> ConfigView.empty(), listener);

            assertThat(contributed[0]).isFalse();
        }
    }

    @Nested
    @DisplayName("what a plugin contributes")
    class Contributions {

        @Test
        @DisplayName("configuration is scoped to the plugin's own namespace")
        void configurationIsScoped() {
            final List<String> seen = new ArrayList<>();
            PluginLoader.load(
                    List.of(plugin("acme", ctx -> seen.add(ctx.configuration().get("mode", "?")))),
                    PluginLoader.acceptAll(),
                    id -> "acme".equals(id.namespace())
                            ? ConfigView.of(Map.of("mode", "strict"))
                            : ConfigView.empty(),
                    listener);

            assertThat(seen).containsExactly("strict");
        }

        @Test
        @DisplayName("metadata is namespaced automatically and sorted")
        void metadataIsNamespacedAndSorted() {
            final PluginRegistry registry = load(
                    plugin("zebra", ctx -> ctx.metadata("k", "z")),
                    plugin("acme", ctx -> ctx.metadata("mode", "strict").metadata("a", "1")));

            assertThat(registry.metadata())
                    .as("this feeds the fingerprint and every woven class, so it must be sorted "
                            + "and must carry its owner")
                    .containsExactly(
                            Map.entry("acme:a", "1"),
                            Map.entry("acme:mode", "strict"),
                            Map.entry("zebra:k", "z"));
        }

        @Test
        @DisplayName("apply observation is opt-in")
        void applyObservationIsOptIn() {
            assertThat(load(plugin("acme", ctx -> { })).hasApplyObservers())
                    .as("with nobody opted in the apply path allocates no event at all, which is "
                            + "what keeps the no-match fast path within its budget")
                    .isFalse();
            reported.clear();
            assertThat(load(plugin("acme", PluginContext::observeApply)).hasApplyObservers())
                    .isTrue();
        }

        @Test
        @DisplayName("a ClassWoven event reaches only the plugins that opted in")
        void classWovenGoesOnlyToObservers() {
            final List<String> heard = new ArrayList<>();
            final PluginRegistry registry = load(
                    observerOptingIn("aaa", event -> heard.add("aaa")),
                    observer("bbb", event -> heard.add("bbb")));

            registry.publish(new PluginEvent.ClassWoven("com/acme/X", 2), listener);

            assertThat(heard).containsExactly("aaa");
        }

        @Test
        @DisplayName("aliases contributed by a factory reach the registry")
        void aliasesArePickedUp() {
            final PluginRegistry registry = load(plugin("acme", ctx -> ctx.points(
                    pointFactory("acme", Set.of("acme:NEW"),
                            Set.of(new Alias("acme:OLD", "acme:NEW", "0.2.0"))))));

            assertThat(registry.points().lookup("acme:OLD", listener)).isPresent();
            assertThat(codes()).containsExactly("AW3120");
        }

        @Test
        @DisplayName("injector kinds are registered under the plugin's namespace")
        void injectorKindsAreRegistered() {
            final PluginRegistry registry = load(plugin("acme", ctx ->
                    ctx.injectors(injectorFactory("acme", InjectorKind.of("acme:wrap")))));

            assertThat(registry.injectors().ids()).containsExactly("acme:wrap");
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    private PluginRegistry load(final WeaverPlugin... plugins) {
        return PluginLoader.load(List.of(plugins), PluginLoader.acceptAll(),
                id -> ConfigView.empty(), this.listener);
    }

    private List<String> codes() {
        return this.reported.stream().map(d -> d.code().code()).toList();
    }

    private static WeaverPlugin plugin(final String namespace,
                                       final Consumer<PluginContext> contributes) {
        return plugin(namespace, WeaverApi.LEVEL, contributes);
    }

    private static WeaverPlugin plugin(final String namespace, final int apiLevel,
                                       final Consumer<PluginContext> contributes) {
        return plugin(namespace, namespace + " plugin", "1.0", apiLevel, contributes);
    }

    private static WeaverPlugin plugin(final String namespace, final String displayName,
                                       final String version, final int apiLevel,
                                       final Consumer<PluginContext> contributes) {
        final PluginId id = new PluginId(namespace, displayName, version);
        return new WeaverPlugin() {
            @Override
            public PluginId id() {
                return id;
            }

            @Override
            public int apiLevel() {
                return apiLevel;
            }

            @Override
            public void contribute(final PluginContext ctx) {
                contributes.accept(ctx);
            }
        };
    }

    private static WeaverPlugin observer(final String namespace,
                                         final Consumer<PluginEvent> observes) {
        return observer(namespace, observes, false);
    }

    private static WeaverPlugin observerOptingIn(final String namespace,
                                                 final Consumer<PluginEvent> observes) {
        return observer(namespace, observes, true);
    }

    private static WeaverPlugin observer(final String namespace,
                                         final Consumer<PluginEvent> observes,
                                         final boolean observeApply) {
        final PluginId id = new PluginId(namespace, namespace + " observer", "1.0");
        return new WeaverPlugin() {
            @Override
            public PluginId id() {
                return id;
            }

            @Override
            public int apiLevel() {
                return WeaverApi.LEVEL;
            }

            @Override
            public void contribute(final PluginContext ctx) {
                if (observeApply) {
                    ctx.observeApply();
                }
            }

            @Override
            public void observe(final PluginEvent event) {
                observes.accept(event);
            }
        };
    }

    private static InjectionPointFactory pointFactory(final String namespace,
                                                      final Set<String> ids) {
        return pointFactory(namespace, ids, Set.of());
    }

    private static InjectionPointFactory pointFactory(final String namespace,
                                                      final Set<String> ids,
                                                      final Set<Alias> aliases) {
        return new InjectionPointFactory() {
            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public Set<String> ids() {
                return ids;
            }

            @Override
            public Set<Alias> aliases() {
                return aliases;
            }

            @Override
            public InjectionPoint create(final String id) {
                throw new UnsupportedOperationException("not needed for these tests");
            }
        };
    }

    private static InjectorFactory injectorFactory(final String namespace,
                                                   final InjectorKind... kinds) {
        return new InjectorFactory() {
            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public Set<InjectorKind> kinds() {
                return Set.of(kinds);
            }

            @Override
            public Injector create(final InjectorKind kind) {
                throw new UnsupportedOperationException("not needed for these tests");
            }
        };
    }
}
