package com.vyrriox.justreplay.voice;

import net.neoforged.fml.ModList;

import java.util.function.Supplier;

/**
 * Link between Just Replay and Simple Voice Chat that never names a voice chat class: the optional plugin
 * installs Opus codec factories here when voice chat loads it. Without voice chat nothing is installed and every
 * voice feature stays idle.
 *
 * @author vyrriox
 */
public final class VoiceBridge {
    public static final String MOD_ID = "voicechat";

    /** One Opus encoder or decoder; stateful, so one per speaker. */
    public interface Codec {
        byte[] encode(short[] pcm);

        short[] decode(byte[] data);

        void close();
    }

    private static volatile Supplier<Codec> encoders;
    private static volatile Supplier<Codec> decoders;
    private static volatile Boolean loaded;

    private VoiceBridge() {
    }

    /** True when Simple Voice Chat is installed. */
    public static boolean isLoaded() {
        Boolean value = loaded;
        if (value == null) {
            value = loaded = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        }
        return value;
    }

    /** Called by the voice chat plugin once the voice chat API is ready. */
    public static void install(Supplier<Codec> encoderFactory, Supplier<Codec> decoderFactory) {
        encoders = encoderFactory;
        decoders = decoderFactory;
    }

    /** A new Opus encoder, or null when voice chat is absent or not initialized. */
    public static Codec newEncoder() {
        return create(encoders);
    }

    /** A new Opus decoder, or null when voice chat is absent or not initialized. */
    public static Codec newDecoder() {
        return create(decoders);
    }

    private static Codec create(Supplier<Codec> factory) {
        if (factory == null || !isLoaded()) {
            return null;
        }
        try {
            return factory.get();
        } catch (Throwable t) {
            return null;
        }
    }
}
