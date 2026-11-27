package com.vyrriox.justreplay.mcp;

import com.vyrriox.justreplay.editor.Marker;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.format.ReplayStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control checks of the self test, over local HTTP against a fake game started on its menu: every check passes,
 * what they added is in project.json, and a server that refuses changes makes them fail instead of passing.
 *
 * @author vyrriox
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpProbeControlTest {
    @TempDir
    Path root;
    private final GameThreadQueue queue = new GameThreadQueue(8);
    private final String token = McpToken.generate();
    private McpHttpServer server;
    private Thread game;
    private volatile boolean running = true;
    private volatile boolean readOnly;
    private FakeObserver world;

    @BeforeEach
    void start() throws Exception {
        ReplayProject project = new ReplayProject();
        project.addMarker(Marker.instant(93, "Marker 1"));
        project.addMarker(Marker.instant(183, "Marker 2"));
        project.normalize();
        FakeControl.writeReplay(root, "test-replay", "Test replay", 231, project);
        world = new FakeObserver();
        world.open = false;
        FakeControl control = new FakeControl(world, root, Files.createDirectories(root.resolve("exports")));
        McpGame view = new McpGame() {
            @Override
            public McpState state() {
                return new McpState();
            }

            @Override
            public OpenReplay openReplay() {
                McpObserver.Replay r = world.replay();
                return r == null ? null : new OpenReplay(r.id(), r.meta(), r.project(), r.tick(), r.durationTicks());
            }

            @Override
            public Path replaysRoot() {
                return root;
            }
        };
        McpRegistry registry = McpReadTools.registry(view);
        McpObserveTools.register(registry, world);
        McpControlTools.register(registry, view, world, control);
        McpProtocol protocol = new McpProtocol(registry, queue, new McpProtocol.Info("justreplay", "Just Replay", "1.2.0", "test"),
                () -> 10_000L, McpProtocol.Guards.of(() -> readOnly, () -> false));
        server = new McpHttpServer(protocol, token, List::of);
        server.start(0);
        game = new Thread(() -> {
            while (running) {
                queue.runPending(5_000_000L);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "fake game thread");
        game.setDaemon(true);
        game.start();
    }

    @AfterEach
    void stop() {
        server.stop();
        running = false;
        game.interrupt();
    }

    @Test
    void everyControlCheckPassesAndWhatItAddedIsSaved() throws Exception {
        McpProbe.Steering s = McpProbe.steer(server.port(), token, new McpProbe.Steer("test-replay", 2, 100, "selftest_mcp"))
                .get(60, TimeUnit.SECONDS);
        assertEquals(19, s.lines().size(), s.lines().toString());
        for (String line : s.lines()) {
            assertTrue(line.endsWith("-> OK"), line);
        }
        ReplayProject saved = ReplayStorage.readProject(root.resolve("test-replay"));
        assertNotNull(saved.camera(s.cameraId()).keyframeAt(s.keyframeTick()));
        assertTrue(saved.actors.stream().anyMatch(a -> a.id.equals(s.actorId())));
        assertNotNull(saved.marker(s.markerId()));
        assertEquals("selftest_mcp.mp4", s.exportFile());
        assertFalse(world.open);
    }

    @Test
    void aServerThatRefusesChangesFailsTheChecks() throws Exception {
        readOnly = true;
        McpProbe.Steering s = McpProbe.steer(server.port(), token, new McpProbe.Steer("test-replay", 2, 100, "selftest_mcp"))
                .get(60, TimeUnit.SECONDS);
        long failed = s.lines().stream().filter(line -> line.endsWith("-> FAIL")).count();
        assertTrue(failed >= 10, s.lines().toString());
        assertTrue(s.lines().get(0).contains("read only"), s.lines().get(0));
    }
}
