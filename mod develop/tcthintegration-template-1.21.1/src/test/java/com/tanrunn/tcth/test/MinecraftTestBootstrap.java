package com.tanrunn.tcth.test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.storage.DataVersion;
import net.neoforged.fml.loading.LoadingModList;

/**
 * Minimal Minecraft bootstrap for unit tests.
 *
 * <p>The ModDevGradle dev artifact contains Minecraft classes patched by
 * NeoForge, whose static initializers depend on a few FML-provided globals
 * that are absent in a bare JUnit JVM. This helper provides exactly those
 * globals and then runs {@link Bootstrap#bootStrap()} once, which brings up
 * the registries needed by {@code ServerLevel}, {@code ItemStack} etc. so they
 * can be mocked/constructed in tests.
 *
 * <p>Required on the test runtime classpath: the ModDevGradle
 * {@code -client-extra-aka-minecraft-resources} artifact (already included via
 * {@code testRuntimeClasspath.extendsFrom(runtimeClasspath)}).
 */
public final class MinecraftTestBootstrap {

    private static boolean bootstrapped = false;

    private MinecraftTestBootstrap() {
    }

    /**
     * Idempotent: safe to call from every test class's {@code @BeforeAll}.
     */
    public static synchronized void bootStrap() {
        if (bootstrapped) {
            return;
        }
        // 1. NeoForge-patched classes query LoadingModList.get(); make it
        //    return an empty list instead of null.
        try {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        } catch (RuntimeException e) {
            // Already set by an earlier classloader or a running environment.
        }
        // 2. SharedConstants.getGameVersion() must not throw "Game version
        //    not set".
        try {
            SharedConstants.setVersion(new TestWorldVersion());
        } catch (IllegalStateException e) {
            // A version was already registered.
        }
        // 3. Full registry bootstrap (registers blocks, items, sounds, …).
        Bootstrap.bootStrap();
        bootstrapped = true;
    }

    private static final class TestWorldVersion implements WorldVersion {

        @Override
        public DataVersion getDataVersion() {
            return new DataVersion(3953);
        }

        @Override
        public String getId() {
            return "1.21.1-test";
        }

        @Override
        public String getName() {
            return "1.21.1-test";
        }

        @Override
        public int getProtocolVersion() {
            return 0;
        }

        @Override
        public int getPackVersion(PackType packType) {
            return 0;
        }

        @Override
        public Date getBuildTime() {
            return new Date(0);
        }

        @Override
        public boolean isStable() {
            return true;
        }
    }
}
