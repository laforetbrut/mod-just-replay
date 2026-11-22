package com.vyrriox.justreplay.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * List values: typed on one line in the settings screen, kept as a JSON array in the file.
 *
 * @author vyrriox
 */
class ConfigFileListTest {
    @TempDir
    Path dir;

    @Test
    void aLineOfCommaSeparatedValuesBecomesAList() {
        assertEquals(List.of("http://localhost:6274", "http://127.0.0.1:3000"),
                ConfigFile.StringListValue.split(" http://localhost:6274 ,, http://127.0.0.1:3000 ,"));
        assertEquals(List.of(), ConfigFile.StringListValue.split("  "));
        assertEquals(List.of(), ConfigFile.StringListValue.split(null));
        assertEquals("a, b", ConfigFile.StringListValue.join(List.of("a", "b")));
        assertEquals("", ConfigFile.StringListValue.join(List.of()));
    }

    @Test
    void aListSurvivesTheFile() {
        Path path = dir.resolve("settings.json");
        ConfigFile file = new ConfigFile(path, "Test.");
        ConfigFile.StringListValue list = file.section("mcp", "Test section.").list("allowedOrigins", List.of(), "Origins.");
        file.load();
        list.set(ConfigFile.StringListValue.split("http://localhost:6274, http://localhost:5173"));

        ConfigFile again = new ConfigFile(path, "Test.");
        ConfigFile.StringListValue read = again.section("mcp", "Test section.").list("allowedOrigins", List.of(), "Origins.");
        again.load();
        assertEquals(List.of("http://localhost:6274", "http://localhost:5173"), read.get());
    }
}
