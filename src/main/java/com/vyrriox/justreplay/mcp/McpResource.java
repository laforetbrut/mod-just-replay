package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonObject;

/**
 * A resource of the MCP server: a fixed URI whose text is read when a client asks for it. Like tools, the reader runs
 * on the game thread unless the resource is built off it.
 *
 * @author vyrriox
 */
public final class McpResource {
    /** Gives the text of the resource. */
    @FunctionalInterface
    public interface Reader {
        String read(McpContext context) throws Exception;
    }

    private final String uri;
    private final String name;
    private final String title;
    private final String description;
    private final String mimeType;
    private final boolean onGameThread;
    private final Reader reader;

    public McpResource(String uri, String name, String title, String description, String mimeType, boolean onGameThread,
                       Reader reader) {
        if (uri == null || uri.isEmpty() || reader == null) {
            throw new IllegalArgumentException("A resource needs a URI and a reader");
        }
        this.uri = uri;
        this.name = name;
        this.title = title;
        this.description = description;
        this.mimeType = mimeType;
        this.onGameThread = onGameThread;
        this.reader = reader;
    }

    public String uri() {
        return uri;
    }

    public String mimeType() {
        return mimeType;
    }

    public boolean onGameThread() {
        return onGameThread;
    }

    Reader reader() {
        return reader;
    }

    JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("uri", uri);
        o.addProperty("name", name);
        if (title != null) {
            o.addProperty("title", title);
        }
        if (description != null) {
            o.addProperty("description", description);
        }
        o.addProperty("mimeType", mimeType);
        return o;
    }

    /**
     * Resources named by a URI template ending with one variable, such as {@code justreplay://markers/{id}}: the part
     * of the URI in place of the variable goes to the reader, which answers null when nothing has that name.
     */
    public static final class Template {
        @FunctionalInterface
        public interface Reader {
            String read(String value, McpContext context) throws Exception;
        }

        private final String uriTemplate;
        private final String prefix;
        private final String name;
        private final String title;
        private final String description;
        private final String mimeType;
        private final boolean onGameThread;
        private final Reader reader;

        public Template(String uriTemplate, String name, String title, String description, String mimeType,
                        boolean onGameThread, Reader reader) {
            int open = uriTemplate.indexOf('{');
            if (open <= 0 || !uriTemplate.endsWith("}") || uriTemplate.indexOf('{', open + 1) >= 0 || reader == null) {
                throw new IllegalArgumentException("A template ends with its only variable: " + uriTemplate);
            }
            this.uriTemplate = uriTemplate;
            this.prefix = uriTemplate.substring(0, open);
            this.name = name;
            this.title = title;
            this.description = description;
            this.mimeType = mimeType;
            this.onGameThread = onGameThread;
            this.reader = reader;
        }

        /** The value of the variable in {@code uri}, or null when the URI is not one of this template. */
        public String match(String uri) {
            if (uri == null || !uri.startsWith(prefix) || uri.length() == prefix.length()) {
                return null;
            }
            String value = uri.substring(prefix.length());
            return value.indexOf('/') >= 0 || value.indexOf('?') >= 0 || value.indexOf('#') >= 0 ? null : value;
        }

        public String mimeType() {
            return mimeType;
        }

        public boolean onGameThread() {
            return onGameThread;
        }

        Reader reader() {
            return reader;
        }

        JsonObject describe() {
            JsonObject o = new JsonObject();
            o.addProperty("uriTemplate", uriTemplate);
            o.addProperty("name", name);
            if (title != null) {
                o.addProperty("title", title);
            }
            if (description != null) {
                o.addProperty("description", description);
            }
            o.addProperty("mimeType", mimeType);
            return o;
        }
    }
}
