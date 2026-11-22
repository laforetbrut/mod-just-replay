package com.vyrriox.justreplay.mcp;

import com.vyrriox.justreplay.editor.Marker;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.format.ReplayMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checks of the in-game self test, against a real server and a fake game: all pass on a working server, and a
 * wrong answer turns its line to FAIL.
 *
 * @author vyrriox
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpProbeTest {
    @TempDir
    Path root;

    private final GameThreadQueue queue = new GameThreadQueue(8);
    private McpHttpServer server;
    private Thread game;
    private volatile boolean running = true;
    private Marker moment;
    private final String token = McpToken.generate();

    @BeforeEach
    void start() throws Exception {
        ReplayProject project = new ReplayProject();
        moment = project.addMarker(Marker.instant(30, "SelfTest moment"));
        moment.note = "Wide shot of the build,\nthen cut to the player";
        project.addMarker(Marker.range(20, 80, 300, "SelfTest stretch"));
        project.normalize();
        McpState state = new McpState();
        state.replay.open = true;
        state.replay.replayId = "replay-1";
        state.replay.tick = 40;
        McpGame.OpenReplay open = new McpGame.OpenReplay("replay-1", new ReplayMeta(), project, 40, 300);
        McpGame fake = new McpGame() {
            @Override
            public McpState state() {
                return state;
            }

            @Override
            public OpenReplay openReplay() {
                return open;
            }

            @Override
            public Path replaysRoot() {
                return root;
            }
        };
        McpProtocol protocol = new McpProtocol(McpReadTools.registry(fake), queue,
                new McpProtocol.Info("justreplay", "Just Replay", "1.2.0", McpReadTools.INSTRUCTIONS), () -> 5000L);
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
    void everyCheckPassesOnAWorkingServer() throws Exception {
        List<String> lines = McpProbe.start(server.port(), token,
                new McpProbe.Expect("replay-1", 2, moment.id, moment.name, moment.note)).get(60, TimeUnit.SECONDS);
        assertEquals(10, lines.size(), lines.toString());
        for (String line : lines) {
            assertTrue(line.endsWith("-> OK"), line);
        }
    }

    @Test
    void aWrongAnswerFails() throws Exception {
        List<String> lines = McpProbe.start(server.port(), token,
                new McpProbe.Expect("replay-1", 3, moment.id, moment.name, "another note")).get(60, TimeUnit.SECONDS);
        long failed = lines.stream().filter(l -> l.endsWith("-> FAIL")).count();
        assertEquals(3, failed, lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("mcp tools/list") && l.endsWith("-> OK")));

        List<String> wrongToken = McpProbe.start(server.port(), McpToken.generate(),
                new McpProbe.Expect("replay-1", 2, moment.id, moment.name, moment.note)).get(60, TimeUnit.SECONDS);
        assertTrue(wrongToken.get(0).endsWith("-> FAIL"), wrongToken.get(0));
    }
}
