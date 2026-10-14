package com.vyrriox.justreplay.export;

import com.vyrriox.justreplay.JustReplay;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the audio track of an export offline. While frames are captured every sound that would play is muted
 * and logged with its frame, position and volume; when the video is done the sounds are decoded, placed in
 * stereo relative to the camera of that frame and mixed into a 48 kHz WAV that FFmpeg adds to the video. The
 * result is in sync with the frames whatever the export speed.
 *
 * @author vyrriox
 */
public final class ExportAudio {
    private static final int RATE = 48_000;

    private record Event(int frame, ResourceLocation file, boolean stream, float volume, float pitch, boolean positional,
                         double x, double y, double z, float range) {
    }

    private record CameraState(double x, double y, double z, float yaw) {
    }

    private final int fps;
    private final List<Event> events = new ArrayList<>();
    private final List<CameraState> cameras = new ArrayList<>();

    public ExportAudio(int fps) {
        this.fps = fps;
    }

    /** Called for every sound while exporting; returns true when the sound was logged (it is muted either way). */
    public void onSound(SoundInstance instance, int frame, boolean capturing) {
        if (!capturing || instance.getSource() == SoundSource.MUSIC || instance.getSource() == SoundSource.MASTER) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        SoundManager manager = mc.getSoundManager();
        try {
            if (instance.resolve(manager) == null) {
                return;
            }
            Sound sound = instance.getSound();
            if (sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
                return;
            }
            float volume = Math.min(1.0F, instance.getVolume()) * mc.options.getSoundSourceVolume(instance.getSource());
            float range = Math.max(instance.getVolume(), 1.0F) * sound.getAttenuationDistance();
            boolean positional = !instance.isRelative() && instance.getAttenuation() != SoundInstance.Attenuation.NONE;
            events.add(new Event(frame, sound.getPath(), sound.shouldStream(), volume, instance.getPitch(), positional,
                    instance.getX(), instance.getY(), instance.getZ(), range));
        } catch (Throwable t) {
            JustReplay.LOGGER.debug("Cannot log sound {}", instance.getLocation(), t);
        }
    }

    /** Camera of a captured frame, used to place the sounds that start on it. */
    public void onFrame(double x, double y, double z, float yaw) {
        cameras.add(new CameraState(x, y, z, yaw));
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    /** Mixes the logged sounds into a WAV file covering {@code frames} video frames. */
    public void writeWav(Path wav, int frames) throws IOException {
        int samples = (int) ((long) frames * RATE / fps);
        float[] left = new float[samples];
        float[] right = new float[samples];
        Map<ResourceLocation, float[][]> decoded = new HashMap<>();
        Minecraft mc = Minecraft.getInstance();
        for (Event event : events) {
            float[][] pcm = decoded.computeIfAbsent(event.file(), f -> decode(mc, f));
            if (pcm == null) {
                continue;
            }
            float[] data = pcm[0];
            int channels = (int) pcm[1][0];
            int sourceRate = (int) pcm[1][1];
            float gainL = event.volume();
            float gainR = event.volume();
            if (event.positional() && channels == 1 && !cameras.isEmpty()) {
                CameraState cam = cameras.get(Math.min(event.frame(), cameras.size() - 1));
                double dx = event.x() - cam.x();
                double dy = event.y() - cam.y();
                double dz = event.z() - cam.z();
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                // OpenAL linear distance model as used by Minecraft.
                float attenuation = (float) Math.max(0.0, 1.0 - distance / Math.max(1.0F, event.range()));
                double yaw = Math.toRadians(cam.yaw());
                double rightX = -Math.cos(yaw);
                double rightZ = -Math.sin(yaw);
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                double pan = horizontal < 1e-6 ? 0.0 : (dx * rightX + dz * rightZ) / horizontal;
                gainL = (float) (event.volume() * attenuation * Math.sqrt((1.0 - pan) / 2.0) * Math.sqrt(2.0));
                gainR = (float) (event.volume() * attenuation * Math.sqrt((1.0 + pan) / 2.0) * Math.sqrt(2.0));
            }
            if (gainL <= 0.0001F && gainR <= 0.0001F) {
                continue;
            }
            int start = (int) ((long) event.frame() * RATE / fps);
            int sourceFrames = data.length / channels;
            double step = (double) sourceRate / RATE * Math.max(0.5F, Math.min(2.0F, event.pitch()));
            double position = 0;
            for (int out = start; out < samples; out++) {
                int index = (int) position;
                if (index + 1 >= sourceFrames) {
                    break;
                }
                double frac = position - index;
                float l;
                float r;
                if (channels == 1) {
                    l = r = (float) (data[index] + (data[index + 1] - data[index]) * frac);
                } else {
                    l = (float) (data[index * channels] + (data[(index + 1) * channels] - data[index * channels]) * frac);
                    r = (float) (data[index * channels + 1] + (data[(index + 1) * channels + 1] - data[index * channels + 1]) * frac);
                }
                left[out] += l * gainL;
                right[out] += r * gainR;
                position += step;
            }
        }
        write(wav, left, right);
    }

    private static float[][] decode(Minecraft mc, ResourceLocation file) {
        try (InputStream in = mc.getResourceManager().open(file); JOrbisAudioStream stream = new JOrbisAudioStream(in)) {
            FloatArrayList samples = new FloatArrayList();
            while (stream.readChunk(samples::add)) {
                if (samples.size() > RATE * 2 * 600) {
                    break; // ten minutes is plenty for one export
                }
            }
            javax.sound.sampled.AudioFormat format = stream.getFormat();
            return new float[][]{samples.toFloatArray(), {format.getChannels(), format.getSampleRate()}};
        } catch (Exception e) {
            JustReplay.LOGGER.debug("Cannot decode sound {}", file, e);
            return null;
        }
    }

    private static void write(Path wav, float[] left, float[] right) throws IOException {
        int samples = left.length;
        try (OutputStream raw = Files.newOutputStream(wav); DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, 1 << 16))) {
            int dataSize = samples * 4;
            out.writeBytes("RIFF");
            out.writeInt(Integer.reverseBytes(36 + dataSize));
            out.writeBytes("WAVEfmt ");
            out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeShort(Short.reverseBytes((short) 2));
            out.writeInt(Integer.reverseBytes(RATE));
            out.writeInt(Integer.reverseBytes(RATE * 4));
            out.writeShort(Short.reverseBytes((short) 4));
            out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data");
            out.writeInt(Integer.reverseBytes(dataSize));
            for (int i = 0; i < samples; i++) {
                out.writeShort(Short.reverseBytes(toPcm(left[i])));
                out.writeShort(Short.reverseBytes(toPcm(right[i])));
            }
        }
    }

    private static short toPcm(float value) {
        // Soft limit so loud overlapping sounds do not clip harshly.
        float v = (float) Math.tanh(value);
        return (short) Math.round(v * 32767.0F);
    }
}
