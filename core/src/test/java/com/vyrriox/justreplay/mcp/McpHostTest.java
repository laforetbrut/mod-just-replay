package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonObject;
import com.vyrriox.justreplay.config.ConfigFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server follows the settings: off by default, a token made on first start, restarts, a busy port.
 *
 * @author vyrriox
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpHostTest {
    @TempDir
    Path dir;

    private ConfigFile file;
    private McpConfig config;
    private McpHost host;
    private final List<String> events = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void setUp() {
        file = new ConfigFile(dir.resolve("justreplay-client.json"), "Test settings.");
        config = McpConfig.define(file);
        file.load();
        McpRegistry registry = new McpRegistry().add(McpTool.builder("thread")
                .description("The name of the thread it runs on.")
                .handler((args, context) -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("thread", Thread.currentThread().getName());
                    return McpToolResult.of(o);
                })
                .build());
        host = new McpHost(config, registry, new McpProtocol.Info("justreplay", "Just Replay", "1.0", null), new McpHost.Events() {
            @Override
            public void started(int port) {
                events.add("started " + port);
            }

            @Override
            public void stopped() {
                events.add("stopped");
            }

            @Override
            public void failed(int port, String reason) {
                events.add("failed " + port + ": " + reason);
            }
        });
    }

    @AfterEach
    void tearDown() {
        host.shutdown();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    private HttpResponse<String> call(int port, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + McpHttpServer.PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"thread\"}}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static boolean open(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void offByDefault() {
        assertFalse(config.enabled.get());
        assertEquals(McpConfig.DEFAULT_PORT, config.port.get());
        host.tick();
        assertEquals(McpHost.Status.OFF, host.status());
        assertEquals(-1, host.port());
        assertTrue(events.isEmpty());
        assertEquals("", config.token.get());
    }

    @Test
    void switchedOnItListensWithANewTokenAndAnswersFromTheTickThread() throws Exception {
        int port = freePort();
        config.port.set(port);
        config.enabled.set(true);
        host.tick();
        assertEquals(McpHost.Status.LISTENING, host.status());
        assertEquals(port, host.port());
        assertEquals(List.of("started " + port), events);
        String token = config.token.get();
        assertTrue(McpToken.usable(token));
        assertTrue(Files.readString(file.path(), StandardCharsets.UTF_8).contains(token), "the token is saved in the settings");

        CompletableFuture<HttpResponse<String>> answer = CompletableFuture.supplyAsync(() -> {
            try {
                return call(port, token);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!answer.isDone() && System.nanoTime() < end) {
            host.tick();
            Thread.sleep(5);
        }
        HttpResponse<String> response = answer.get(1, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"thread\":\"" + Thread.currentThread().getName() + "\""), response.body());
        assertEquals(1, host.calls());
        assertTrue(host.lastCallMillis() > 0);

        assertEquals(401, call(port, McpToken.generate()).statusCode());
    }

    @Test
    void portAndTokenChangesRestartAndSwitchingOffStops() throws Exception {
        int first = freePort();
        config.port.set(first);
        config.enabled.set(true);
        host.tick();
        String token = config.token.get();
        int second = freePort();
        config.port.set(second);
        host.tick();
        assertEquals(second, host.port());
        assertFalse(open(first), "the old port is closed");
        assertTrue(open(second));

        config.token.set(McpToken.generate());
        host.tick();
        assertNotEquals(token, config.token.get());
        assertEquals(McpHost.Status.LISTENING, host.status());

        config.token.set("short");
        host.tick();
        assertTrue(McpToken.usable(config.token.get()), "a token too short is replaced");

        config.enabled.set(false);
        host.tick();
        assertEquals(McpHost.Status.OFF, host.status());
        assertFalse(open(second));
        assertEquals("stopped", events.get(events.size() - 1));
    }

    @Test
    void aBusyPortFailsOnceUntilTheSettingsChange() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            config.port.set(taken.getLocalPort());
            config.enabled.set(true);
            host.tick();
            host.tick();
            host.tick();
            assertEquals(McpHost.Status.FAILED, host.status());
            assertTrue(host.error().contains("already used"), host.error());
            assertEquals(1, events.size());
            assertTrue(events.get(0).startsWith("failed " + taken.getLocalPort()));
        }
        int port = freePort();
        config.port.set(port);
        host.tick();
        assertEquals(McpHost.Status.LISTENING, host.status());
        assertEquals("started " + port, events.get(1));
    }

    @Test
    void theSettingsSectionIsWrittenWithEveryOption() throws Exception {
        String text = Files.readString(file.path(), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"mcp\": {"));
        for (String key : List.of("enabled", "port", "token", "timeoutSeconds", "allowedOrigins")) {
            assertTrue(text.contains("\"" + key + "\""), key);
        }
        assertThrows(IllegalArgumentException.class, () -> new McpHttpServer(null, "short", List::of));
    }
}
