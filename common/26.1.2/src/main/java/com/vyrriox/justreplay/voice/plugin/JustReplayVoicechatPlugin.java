package com.vyrriox.justreplay.voice.plugin;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.voice.VoiceBridge;
import com.vyrriox.justreplay.voice.VoiceRecorder;
import com.vyrriox.justreplay.voice.VoiceTrack;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;

/**
 * Simple Voice Chat plugin of Just Replay. Voice chat finds it through the {@link ForgeVoicechatPlugin}
 * annotation and is the only one to load it, so this is the only class (with its nested codec) that names voice
 * chat types; without voice chat it is never loaded. It hands received voices and the local microphone to the
 * recorder and installs Opus codecs for recording and playback.
 *
 * @author vyrriox
 */
@ForgeVoicechatPlugin
public final class JustReplayVoicechatPlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return JustReplay.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        VoiceBridge.install(() -> new OpusCodec(api.createEncoder(OpusEncoderMode.VOIP), null),
                () -> new OpusCodec(null, api.createDecoder()));
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, event ->
                VoiceRecorder.onVoice(VoiceTrack.ENTITY, event.getEntityId(), Double.NaN, Double.NaN, Double.NaN,
                        event.getDistance(), event.getRawAudio()));
        registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, event -> {
            Position position = event.getPosition();
            VoiceRecorder.onVoice(VoiceTrack.LOCATIONAL, event.getId(), position.getX(), position.getY(), position.getZ(),
                    event.getDistance(), event.getRawAudio());
        });
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, event ->
                VoiceRecorder.onVoice(VoiceTrack.STATIC, event.getId(), Double.NaN, Double.NaN, Double.NaN, 0.0F, event.getRawAudio()));
        registration.registerEvent(ClientSoundEvent.class, event ->
                VoiceRecorder.onMicrophone((float) event.getVoicechat().getVoiceChatDistance(), event.getRawAudio()));
    }

    /** Opus encoder or decoder of the voice chat API. */
    private static final class OpusCodec implements VoiceBridge.Codec {
        private final OpusEncoder encoder;
        private final OpusDecoder decoder;

        OpusCodec(OpusEncoder encoder, OpusDecoder decoder) {
            this.encoder = encoder;
            this.decoder = decoder;
        }

        @Override
        public byte[] encode(short[] pcm) {
            return encoder.encode(pcm);
        }

        @Override
        public short[] decode(byte[] data) {
            return decoder.decode(data);
        }

        @Override
        public void close() {
            if (encoder != null) {
                encoder.close();
            }
            if (decoder != null) {
                decoder.close();
            }
        }
    }
}
