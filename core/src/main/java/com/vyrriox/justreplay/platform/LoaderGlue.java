package com.vyrriox.justreplay.platform;

/**
 * Finds the classes of the loader folder by name. The lookup is lazy and needs no set-up call, so a class that reads the
 * platform from a static initializer works however early it is loaded.
 *
 * @author vyrriox
 */
final class LoaderGlue {
    private static final String BASE = "com.vyrriox.justreplay.";
    private static final String[][] LOADERS = {{"neoforge", "NeoForge"}, {"forge", "Forge"}, {"fabric", "Fabric"}};

    private LoaderGlue() {
    }

    /** Creates {@code <base>.<loader>.<Loader><suffix>} for the loader whose class is in this jar. */
    static <T> T create(Class<T> type, String suffix) {
        for (String[] loader : LOADERS) {
            Class<?> found;
            try {
                found = Class.forName(BASE + loader[0] + "." + loader[1] + suffix, true, LoaderGlue.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                continue;
            }
            try {
                return type.cast(found.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot create " + found.getName(), e);
            }
        }
        throw new IllegalStateException("No loader class for " + type.getName());
    }
}
