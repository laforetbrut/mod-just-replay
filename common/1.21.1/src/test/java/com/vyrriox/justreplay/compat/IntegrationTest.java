package com.vyrriox.justreplay.compat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An integration whose hook fails turns itself off with a single warning, whatever the error and the thread.
 *
 * @author vyrriox
 */
class IntegrationTest {
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    private Integration on(String modId) {
        return new Integration(modId, "Test Mod", Integration.State.ON, (message, error) -> warnings.add(message));
    }

    @Test
    void failingHookTurnsTheIntegrationOffWithOneWarning() {
        Integration integration = on("testmod");
        int[] calls = {0};
        Integration.Hook render = () -> {
            calls[0]++;
            throw new NoSuchMethodError("testmod.client.Renderer.render(F)V");
        };
        integration.run("render", render);
        assertFalse(integration.on());
        assertEquals(Integration.State.FAILED, integration.state());
        assertEquals("render", integration.failedHook());

        integration.run("render", render);
        integration.run("tick", render);
        assertFalse(integration.test("blockServerbound", () -> true));
        assertEquals(1, calls[0], "a failed integration runs no other hook");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Test Mod") && warnings.get(0).contains("render")
                && warnings.get(0).contains("compat.testmod"), warnings.get(0));
    }

    @Test
    void everyKindOfErrorIsCaught() {
        List<Throwable> errors = List.of(new NoClassDefFoundError("testmod/Api"), new LinkageError("changed"),
                new IncompatibleClassChangeError("field became static"), new AbstractMethodError("render"),
                new ClassCastException("not a testmod packet"), new IllegalStateException("not ready"),
                new StackOverflowError(), new Exception("checked, from reflection"));
        for (Throwable error : errors) {
            Integration integration = on("testmod");
            integration.run("hook", () -> {
                throw error;
            });
            assertFalse(integration.on(), error.toString());
        }
        assertEquals(errors.size(), warnings.size());
    }

    @Test
    void yesOrNoHookAnswersNoWhenItFails() {
        Integration integration = on("testmod");
        assertTrue(integration.test("check", () -> true));
        assertFalse(integration.test("check", () -> {
            throw new NoSuchFieldError("state");
        }));
        assertFalse(integration.test("check", () -> true), "off after the failure");
    }

    @Test
    void integrationOffInTheConfigNeverRuns() {
        Integration integration = Integration.of("testmod", "Test Mod", true, true, false);
        assertEquals(Integration.State.OFF_IN_CONFIG, integration.state());
        assertFalse(integration.on());
        assertFalse(integration.started());
        integration.run("tick", () -> fail("an integration off in the config must not run"));
        integration.cleanup("onReplayEnd", () -> fail("nothing to put back"));
        assertFalse(integration.test("check", () -> true));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void absentOrUnsupportedModStaysOff() {
        assertEquals(Integration.State.ABSENT, Integration.of("testmod", "Test Mod", false, true, true).state());
        Integration unsupported = Integration.of("testmod", "Test Mod", true, false, true);
        assertEquals(Integration.State.UNSUPPORTED, unsupported.state());
        assertFalse(unsupported.on());
        assertEquals(Integration.State.ON, Integration.of("testmod", "Test Mod", true, true, true).state());
    }

    @Test
    void cleanupStillRunsAfterAFailureWithoutANewWarning() {
        Integration integration = on("testmod");
        integration.run("tick", () -> {
            throw new RuntimeException("broken tick");
        });
        boolean[] cleaned = {false};
        integration.cleanup("onReplayEnd", () -> cleaned[0] = true);
        assertTrue(cleaned[0], "what the integration changed is put back");
        integration.cleanup("onReplayEnd", () -> {
            throw new RuntimeException("broken again");
        });
        assertEquals(1, warnings.size());
    }

    @Test
    void failuresOnManyThreadsWarnOnce() throws Exception {
        Integration integration = on("testmod");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                done.add(pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < 200; j++) {
                        integration.run("network", () -> {
                            throw new NoClassDefFoundError("testmod/Packet");
                        });
                        integration.fail("network", new IllegalStateException());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : done) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }
        assertFalse(integration.on());
        assertEquals(1, warnings.size());
    }

    @Test
    void startupLineTellsTheStateOfEachInstalledIntegration() {
        Integration curios = Integration.of("curios", "Curios", true, true, true);
        Integration geckolib = Integration.of("geckolib", "GeckoLib", true, true, false);
        Integration morph = Integration.of("morph", "Morph", true, false, true);
        Integration jade = new Integration("jade", "Jade", Integration.State.ON, (message, error) -> warnings.add(message));
        jade.fail("tick", new NoSuchMethodError("snownee.jade.Jade.tick()V"));
        Integration pehkui = Integration.of("pehkui", "Pehkui", false, true, true);
        String line = Integration.startupLine("1.1.2", "neoforge", "21.1.221", "1.21.1",
                List.of(curios, geckolib, morph, jade, pehkui), Set.of("curios", "geckolib", "morph", "jade"));
        assertEquals("Just Replay 1.1.2 on neoforge 21.1.221, Minecraft 1.21.1. Integrations on: Curios; off in the config: GeckoLib;"
                + " installed but not supported on this loader: Morph; failed: Jade (in tick); not installed: 1.", line);
    }
}
