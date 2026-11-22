package com.vyrriox.justreplay.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools and resources the server offers, in the order they were added (lists stay the same from one call to
 * the next, which clients cache better).
 *
 * @author vyrriox
 */
public final class McpRegistry {
    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final Map<String, McpResource> resources = new LinkedHashMap<>();
    private final List<McpResource.Template> templates = new ArrayList<>();

    public synchronized McpRegistry add(McpTool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalArgumentException("Tool " + tool.name() + " added twice");
        }
        return this;
    }

    public synchronized McpRegistry add(McpResource resource) {
        if (resources.putIfAbsent(resource.uri(), resource) != null) {
            throw new IllegalArgumentException("Resource " + resource.uri() + " added twice");
        }
        return this;
    }

    public synchronized McpRegistry add(McpResource.Template template) {
        templates.add(template);
        return this;
    }

    public synchronized McpTool tool(String name) {
        return tools.get(name);
    }

    public synchronized List<McpTool> tools() {
        return Collections.unmodifiableList(new ArrayList<>(tools.values()));
    }

    public synchronized McpResource resource(String uri) {
        return resources.get(uri);
    }

    public synchronized List<McpResource> resources() {
        return Collections.unmodifiableList(new ArrayList<>(resources.values()));
    }

    public synchronized List<McpResource.Template> templates() {
        return Collections.unmodifiableList(new ArrayList<>(templates));
    }
}
