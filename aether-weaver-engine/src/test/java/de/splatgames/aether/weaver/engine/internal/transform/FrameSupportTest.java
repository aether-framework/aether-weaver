package de.splatgames.aether.weaver.engine.internal.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FrameSupportTest {

    private static final int CHAIN_DEPTH = 40;
    private static final int THREADS = 8;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("resource-parsing resolution survives concurrent weaving of a woven hierarchy")
    void resourceParsingSurvivesConcurrentWeaving() throws Exception {
        final List<byte[]> chain = generateChain();
        final ChainLoader loader = new ChainLoader(chain);
        final ClassFile classFile = FrameSupport.forLoadTime(loader);
        final AtomicInteger woven = new AtomicInteger();

        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<Integer>> futures = new ArrayList<>();

            for (int thread = 0; thread < THREADS; thread++) {
                final int offset = thread * 5;
                futures.add(pool.submit(() -> {
                    start.await();
                    int local = 0;
                    for (int i = 0; i < CHAIN_DEPTH; i++) {
                        final int index = (i + offset) % CHAIN_DEPTH;
                        final byte[] original = chain.get(index);
                        final byte[] result = classFile.transformClass(
                                classFile.parse(original), ClassTransform.ACCEPT_ALL);
                        assertThat(classFile.verify(result)).isEmpty();
                        woven.incrementAndGet();
                        local++;
                    }
                    return local;
                }));
            }

            start.countDown();
            for (final Future<Integer> future : futures) {
                // A deadlock manifests as this timing out rather than as a failed assertion.
                assertThat(future.get(45, TimeUnit.SECONDS)).isEqualTo(CHAIN_DEPTH);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(woven.get()).isEqualTo(THREADS * CHAIN_DEPTH);
    }

    @Test
    @DisplayName("the load-time configuration never resolves by loading classes")
    void loadTimeConfigurationDoesNotLoadClasses() {
        // Resolution must go through class *resources*. A loader that records every loadClass
        // call proves the resolver never takes that path, which is the property the whole
        // deadlock argument rests on.
        final List<byte[]> chain = generateChain();
        final RecordingLoader loader = new RecordingLoader(chain);
        final ClassFile classFile = FrameSupport.forLoadTime(loader);

        for (final byte[] original : chain) {
            final byte[] result =
                    classFile.transformClass(classFile.parse(original), ClassTransform.ACCEPT_ALL);
            assertThat(classFile.verify(result)).isEmpty();
        }

        assertThat(loader.loadedNames)
                .as("hierarchy resolution must read resources, never load classes")
                .isEmpty();
        assertThat(loader.resourceNames)
                .as("and it must actually have consulted the resources")
                .isNotEmpty();
    }

    @Test
    @DisplayName("build-time and load-time configurations resolve identically")
    void bothConfigurationsAgree() {
        // One strategy for both, so a class woven at build time and the same class woven at load
        // time cannot diverge because their hierarchies were resolved differently.
        final List<byte[]> chain = generateChain();
        final ChainLoader loader = new ChainLoader(chain);

        final ClassFile loadTime = FrameSupport.forLoadTime(loader);
        final ClassFile buildTime = FrameSupport.forBuildTime(loader);

        for (final byte[] original : chain) {
            assertThat(buildTime.transformClass(buildTime.parse(original), ClassTransform.ACCEPT_ALL))
                    .isEqualTo(loadTime.transformClass(
                            loadTime.parse(original), ClassTransform.ACCEPT_ALL));
        }
    }

    // -------------------------------------------------------------------------------------

    private static String chainName(final int index) {
        return "chain/K" + index;
    }

    private static List<byte[]> generateChain() {
        // Generating K(n) needs K(n-1)'s hierarchy for frame computation, and these classes exist
        // nowhere yet. Seed a resolver with the structure we are about to create.
        final java.util.Map<ClassDesc, ClassDesc> superclasses = new java.util.HashMap<>();
        for (int i = 0; i < CHAIN_DEPTH; i++) {
            superclasses.put(ClassDesc.ofInternalName(chainName(i)),
                    i == 0 ? ConstantDescs.CD_Object
                            : ClassDesc.ofInternalName(chainName(i - 1)));
        }
        final ClassFile classFile = ClassFile.of(
                ClassFile.StackMapsOption.GENERATE_STACK_MAPS,
                ClassFile.ClassHierarchyResolverOption.of(
                        java.lang.classfile.ClassHierarchyResolver.of(List.of(), superclasses)
                                .orElse(java.lang.classfile.ClassHierarchyResolver
                                        .defaultResolver())));
        final List<byte[]> chain = new ArrayList<>(CHAIN_DEPTH);
        for (int i = 0; i < CHAIN_DEPTH; i++) {
            final int index = i;
            final ClassDesc self = ClassDesc.ofInternalName(chainName(index));
            final ClassDesc parent = index == 0
                    ? ConstantDescs.CD_Object
                    : ClassDesc.ofInternalName(chainName(index - 1));

            chain.add(classFile.build(self, cb -> {
                cb.withSuperclass(parent);
                cb.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void,
                        ClassFile.ACC_PUBLIC,
                        code -> code.aload(0)
                                .invokespecial(parent, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                                .return_());
                if (index > 0) {
                    cb.withMethodBody("merge",
                            MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_boolean),
                            ClassFile.ACC_PUBLIC,
                            code -> code.iload(1).ifThenElse(
                                    thenBlock -> thenBlock.new_(parent).dup()
                                            .invokespecial(parent, ConstantDescs.INIT_NAME,
                                                    ConstantDescs.MTD_void),
                                    elseBlock -> elseBlock.aload(0))
                                    .areturn());
                }
            }));
        }
        return chain;
    }

    private static class ChainLoader extends ClassLoader {
        private final List<byte[]> chain;

        ChainLoader(final List<byte[]> chain) {
            super(FrameSupportTest.class.getClassLoader());
            this.chain = chain;
        }

        int indexOf(final String resourceName) {
            for (int i = 0; i < CHAIN_DEPTH; i++) {
                if (resourceName.equals(chainName(i) + ".class")) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public java.io.InputStream getResourceAsStream(final String name) {
            final int index = indexOf(name);
            return index >= 0
                    ? new java.io.ByteArrayInputStream(this.chain.get(index))
                    : super.getResourceAsStream(name);
        }
    }

    private static final class RecordingLoader extends ChainLoader {
        private final List<String> loadedNames =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<String> resourceNames =
                java.util.Collections.synchronizedList(new ArrayList<>());

        RecordingLoader(final List<byte[]> chain) {
            super(chain);
        }

        @Override
        public java.io.InputStream getResourceAsStream(final String name) {
            if (indexOf(name) >= 0) {
                this.resourceNames.add(name);
            }
            return super.getResourceAsStream(name);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith("chain.")) {
                this.loadedNames.add(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
