package es.yori.sleeptrain;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Applies the same automatic output policy to the built-in tone and imported audio. */
final class AlarmPlayer {
    static final String SPEAKER_ENABLED = "alarmSpeakerEnabled";
    static final String HEADPHONES_ENABLED = "alarmHeadphonesEnabled";
    private final Context context;
    private final SharedPreferences preferences;
    private final AudioManager audio;
    private final Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Consumer<String> status;
    private AudioTrack track;
    private MediaPlayer media;
    private AudioFocusRequest focus;
    private File customFile;
    private boolean bundledSecret;
    private String mode;
    private boolean fallback, active, headphonesWereConnected, mediaPrepared, hasFocus;
    private int generation;
    private long routeReadyAt;
    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { reroute(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { reroute(); }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener outputsChanged = (preferences, key) -> {
        if (SPEAKER_ENABLED.equals(key) || HEADPHONES_ENABLED.equals(key)) reroute();
    };

    AlarmPlayer(Context context, Consumer<String> status) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        audio = context.getSystemService(AudioManager.class);
        vibrator = context.getSystemService(Vibrator.class);
        this.status = status;
    }

    void start(String mode, boolean vibrate, boolean fallback) {
        start(mode, vibrate, fallback, "");
    }

    void start(String mode, boolean vibrate, boolean fallback, String audioId) {
        stop();
        this.mode = mode;
        this.fallback = fallback;
        customFile = AlarmAudio.file(context, audioId);
        bundledSecret = AlarmAudio.isBundledSecret(audioId);
        headphonesWereConnected = false;
        active = true;
        if (vibrate) vibrate();
        preferences.registerOnSharedPreferenceChangeListener(outputsChanged);
        audio.registerAudioDeviceCallback(devices, handler);
        reroute();
    }

    private void reroute() {
        if (!active) return;
        handler.removeCallbacksAndMessages(null);
        releasePlayback();
        AudioDeviceInfo speaker = null;
        AudioDeviceInfo headphones = null;
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) speaker = device;
            if (isHeadphone(device)) headphones = device;
        }
        boolean wantHeadphones = !"speaker".equals(mode) && headphones != null;
        if (wantHeadphones) headphonesWereConnected = true;
        if (!soundAllowed(wantHeadphones)) return;
        if (!"speaker".equals(mode) && headphones == null
                && ("headphones".equals(mode) || headphonesWereConnected)) {
            vibrate();
            if (!fallback) { status.accept("Auriculares desconectados · solo vibración"); return; }
        }
        AudioDeviceInfo target = wantHeadphones ? headphones : speaker;
        if (target == null) { vibrate(); status.accept("Salida no disponible · vibración"); return; }
        play(target, wantHeadphones);
    }

    private void play(AudioDeviceInfo target, boolean headphones) {
        if (!soundAllowed(headphones)) return;
        boolean recording = bundledSecret || customFile != null;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(headphones ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ALARM)
                .setContentType(recording ? AudioAttributes.CONTENT_TYPE_MUSIC : AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        int currentGeneration = generation;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                    if (!active || generation != currentGeneration) return;
                    if (change < 0) {
                        hasFocus = false;
                        volume(0);
                        vibrate();
                        status.accept("Audio interrumpido · vibración");
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        hasFocus = true;
                        verifyPlayback(target, headphones);
                    }
                }, handler).build();
        hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!hasFocus) {
            releasePlayback();
            vibrate();
            status.accept("No se pudo activar el audio · vibración");
            return;
        }
        try {
            // Older MediaPlayer versions cannot confirm that a muted output is being avoided.
            if (!recording || Build.VERSION.SDK_INT < 28 && !soundEnabled(!headphones)) playTone(attributes, target, headphones);
            else playRecording(attributes, target, headphones);
        } catch (IOException | RuntimeException error) {
            if (recording) useBuiltInTone(target, headphones);
            else noPlayback("Audio no disponible · vibración");
        }
    }

    private void playRecording(AudioAttributes attributes, AudioDeviceInfo target, boolean headphones) throws IOException {
        MediaPlayer current = new MediaPlayer();
        media = current;
        current.setAudioAttributes(attributes);
        if (bundledSecret) {
            try (AssetFileDescriptor input = context.getResources().openRawResourceFd(R.raw.keyboard_cat)) {
                if (input == null) throw new IOException("No se pudo abrir el tono secreto");
                current.setDataSource(input.getFileDescriptor(), input.getStartOffset(), input.getLength());
            }
        } else {
            try (FileInputStream input = new FileInputStream(customFile)) {
                current.setDataSource(input.getFD());
            }
        }
        current.setOnPreparedListener(prepared -> {
            if (prepared != media || !active) return;
            try {
                mediaPrepared = true;
                prepared.setLooping(true);
                prepared.setVolume(0, 0);
                routeReadyAt = SystemClock.elapsedRealtime() + 750;
                AudioRouting output = routing(prepared);
                if (output != null) {
                    if (!output.setPreferredDevice(target)) {
                        outputNotVerified(headphones);
                        return;
                    }
                    watchRoute(output, target, headphones);
                }
                prepared.start();
                handler.postDelayed(() -> {
                    if (prepared == media) verifyPlayback(target, headphones);
                }, 750);
            } catch (RuntimeException error) { useBuiltInTone(target, headphones); }
        });
        current.setOnErrorListener((failed, what, extra) -> {
            if (failed == media && active) useBuiltInTone(target, headphones);
            return true;
        });
        current.prepareAsync();
        handler.postDelayed(() -> {
            if (current == media && active && !mediaPrepared) useBuiltInTone(target, headphones);
        }, 4_000);
    }

    private void useBuiltInTone(AudioDeviceInfo target, boolean headphones) {
        releasePlayback();
        customFile = null;
        bundledSecret = false;
        if (active) play(target, headphones);
    }

    private void playTone(AudioAttributes attributes, AudioDeviceInfo target, boolean headphones) {
        int rate = 44_100;
        short[] tone = new short[rate];
        for (int i = 0; i < tone.length; i++) {
            double phase = i / (double) rate;
            double envelope = (phase < .28 || (phase > .4 && phase < .68))
                    ? Math.min(1, Math.min((phase % .4) * 40, (.28 - phase % .4) * 40)) : 0;
            tone[i] = (short) (Math.max(0, envelope) * 12_000 * Math.sin(2 * Math.PI * (phase < .4 ? 660 : 880) * phase));
        }
        AudioTrack current = new AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(tone.length * 2).build();
        track = current;
        if (!current.setPreferredDevice(target)) {
            outputNotVerified(headphones);
            return;
        }
        if (current.write(tone, 0, tone.length) != tone.length || current.setLoopPoints(0, tone.length, -1) != AudioTrack.SUCCESS) {
            throw new IllegalStateException("No se pudo cargar el tono");
        }
        current.setVolume(0);
        routeReadyAt = SystemClock.elapsedRealtime() + 350;
        current.play();
        watchRoute(current, target, headphones);
        handler.postDelayed(() -> verifyRoute(current, target, headphones), 350);
    }

    private void watchRoute(AudioRouting output, AudioDeviceInfo target, boolean headphones) {
        output.addOnRoutingChangedListener(router -> {
            if (output != track && output != media) return;
            volume(0);
            long delay = Math.max(250, routeReadyAt - SystemClock.elapsedRealtime());
            handler.postDelayed(() -> verifyRoute(output, target, headphones), delay);
        }, handler);
    }

    private void verifyPlayback(AudioDeviceInfo target, boolean headphones) {
        if (track != null) verifyRoute(track, target, headphones);
        else if (media != null && mediaPrepared && hasFocus) {
            AudioRouting output = routing(media);
            if (output != null) verifyRoute(output, target, headphones);
            // Before API 28 MediaPlayer relies on Android's automatic output for the selected usage.
            else reportPlayback(headphones, false);
        }
    }

    private static AudioRouting routing(Object player) {
        return player instanceof AudioRouting ? (AudioRouting) player : null;
    }

    private void verifyRoute(AudioRouting current, AudioDeviceInfo requested, boolean headphones) {
        if (!active || !hasFocus || (current != track && current != media) || SystemClock.elapsedRealtime() < routeReadyAt) return;
        AudioDeviceInfo device = Build.VERSION.SDK_INT >= 36 ? null : current.getRoutedDevice();
        List<AudioDeviceInfo> routed = Build.VERSION.SDK_INT >= 36 ? current.getRoutedDevices()
                : device == null ? Collections.emptyList() : Collections.singletonList(device);
        boolean matches = !routed.isEmpty();
        for (AudioDeviceInfo actual : routed) matches &= headphones ? isHeadphone(actual) : actual.getId() == requested.getId();
        if (matches) reportPlayback(headphones, true);
        else outputNotVerified(headphones);
    }

    private void reportPlayback(boolean headphones, boolean verified) {
        if (!soundAllowed(headphones)) { releasePlayback(); return; }
        volume(.8f);
        int stream = headphones ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_ALARM;
        if (audio.getStreamVolume(stream) == 0) {
            vibrate();
            status.accept("Volumen del sistema a cero · vibración de respaldo");
        } else {
            status.accept(!verified ? "Aviso en reproducción" : headphones ? "Aviso por auriculares" : "Aviso por altavoz");
        }
    }

    private void outputNotVerified(boolean headphones) {
        releasePlayback();
        vibrate();
        if (headphones && fallback && soundEnabled(false)) {
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { play(device, false); return; }
            }
        }
        status.accept("El móvil no confirmó la salida elegida · vibración");
    }

    private void noPlayback(String message) {
        releasePlayback();
        vibrate();
        status.accept(message);
    }

    private boolean soundEnabled(boolean headphones) {
        return preferences.getBoolean(headphones ? HEADPHONES_ENABLED : SPEAKER_ENABLED, true);
    }

    private boolean soundAllowed(boolean headphones) {
        if (soundEnabled(headphones)) return true;
        status.accept(headphones ? "Sonido desactivado en auriculares" : "Sonido desactivado en altavoz");
        return false;
    }

    private void volume(float gain) {
        if (track != null) track.setVolume(gain);
        if (media != null && mediaPrepared) media.setVolume(gain, gain);
    }

    private static boolean isHeadphone(AudioDeviceInfo device) {
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || (Build.VERSION.SDK_INT >= 28 && type == AudioDeviceInfo.TYPE_HEARING_AID)
                || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }

    private void vibrate() {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 250, 500, 700}, 0));
        }
    }

    private void releasePlayback() {
        generation++;
        hasFocus = false;
        mediaPrepared = false;
        if (track != null) { track.release(); track = null; }
        if (media != null) { media.release(); media = null; }
        if (focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
    }

    void stop() {
        if (active) {
            preferences.unregisterOnSharedPreferenceChangeListener(outputsChanged);
            audio.unregisterAudioDeviceCallback(devices);
        }
        active = false;
        handler.removeCallbacksAndMessages(null);
        releasePlayback();
        if (vibrator != null) vibrator.cancel();
    }
}
