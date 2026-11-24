package es.yori.sleeptrain;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.BaseCursor;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.util.DataSource;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AlarmAudioTest {
    private static final Uri DOCUMENT = Uri.parse("content://test.documents/track");

    @Test public void selectedAudioRemainsAvailableWithoutAccessToTheOriginalDocument() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowContentResolver resolver = shadowOf(context.getContentResolver());
        byte[] contents = {12, 34, 56, 78};
        resolver.registerInputStream(DOCUMENT, new ByteArrayInputStream(contents));
        resolver.setCursor(DOCUMENT, new BaseCursor() {
            @Override public boolean moveToFirst() { return true; }
            @Override public int getColumnIndex(String name) { return OpenableColumns.DISPLAY_NAME.equals(name) ? 0 : -1; }
            @Override public String getString(int index) { return "Mi alarma.mp3"; }
            @Override public void close() { }
        });
        DataSource.setFileDescriptorTransform((fd, offset) -> "imported-alarm");
        ShadowMediaExtractor.addTrack(DataSource.toDataSource("imported-alarm"),
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 44_100, 1), contents);
        try {
            AlarmAudio.Choice selected = AlarmAudio.importAudio(context, DOCUMENT);
            resolver.registerInputStreamSupplier(DOCUMENT, () -> { throw new SecurityException("Revoked"); });
            File copied = AlarmAudio.file(context, selected.id);
            assertNotNull(copied);
            assertArrayEquals(contents, Files.readAllBytes(copied.toPath()));
            assertEquals("Mi alarma.mp3", AlarmAudio.name(context, selected.id));
        } finally {
            DataSource.reset();
        }
    }

    @Test public void unreadableAudioIsNotSavedAndMissingSoundUsesTheBuiltInLabel() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowContentResolver resolver = shadowOf(context.getContentResolver());
        resolver.registerInputStream(DOCUMENT, new ByteArrayInputStream(new byte[]{12, 34, 56}));
        assertThrows(IOException.class, () -> AlarmAudio.importAudio(context, DOCUMENT));
        File directory = new File(context.getFilesDir(), "alarm_audio");
        String[] files = directory.list();
        assertNotNull(files);
        assertEquals(0, files.length);
        assertEquals(AlarmAudio.DEFAULT_NAME, AlarmAudio.name(context, ""));
    }

    @Test public void theBundledToneStaysAvailableAfterUnlocking() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals("", AlarmAudio.secretTone(context));
        String unlocked = AlarmAudio.unlockSecretTone(context);
        assertEquals(unlocked, AlarmAudio.secretTone(context));
        assertTrue(AlarmAudio.isBundledSecret(unlocked));
        assertEquals(AlarmAudio.SECRET_NAME, AlarmAudio.name(context, unlocked));
        try (var clip = context.getResources().openRawResource(R.raw.keyboard_cat)) {
            assertTrue(clip.read() >= 0);
        }
    }

    @Test public void aPreviouslyImportedSecretToneRemainsAvailableAfterUnlocking() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals("", AlarmAudio.secretTone(context));
        byte[] contents = {12, 34, 56, 78};
        ShadowContentResolver resolver = shadowOf(context.getContentResolver());
        resolver.registerInputStream(DOCUMENT, new ByteArrayInputStream(contents));
        DataSource.setFileDescriptorTransform((fd, offset) -> "secret-alarm");
        ShadowMediaExtractor.addTrack(DataSource.toDataSource("secret-alarm"),
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 44_100, 1), contents);
        try {
            AlarmAudio.Choice saved = AlarmAudio.importAudio(context, DOCUMENT);
            context.getSharedPreferences("alarm_audio", 0).edit().putString("secretToneId", saved.id).apply();
            AlarmAudio.unlockSecretTone(context);
            resolver.registerInputStreamSupplier(DOCUMENT, () -> { throw new SecurityException("Revoked"); });
            assertEquals(saved.id, AlarmAudio.importedSecretTone(context));
            assertEquals(AlarmAudio.SECRET_IMPORT_NAME, AlarmAudio.name(context, saved.id));
            assertArrayEquals(contents, Files.readAllBytes(AlarmAudio.file(context, saved.id).toPath()));
        } finally { DataSource.reset(); }
    }

    @Test public void mutedHeadphonesStayQuietAndDisconnectionRespectsTheSpeakerSetting() {
        Context context = RuntimeEnvironment.getApplication();
        var preferences = context.getSharedPreferences("preferences", 0);
        preferences.edit().putBoolean(AlarmPlayer.HEADPHONES_ENABLED, false).apply();
        AudioDeviceInfo speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build();
        AudioDeviceInfo headphones = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES).build();
        ShadowAudioManager audio = shadowOf(context.getSystemService(AudioManager.class));
        audio.setOutputDevices(Arrays.asList(speaker, headphones));
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
        AtomicReference<String> status = new AtomicReference<>();
        AlarmPlayer player = new AlarmPlayer(context, status::set);
        try {
            player.start("auto", false, true);
            assertEquals("Sonido desactivado en auriculares", status.get());
            assertNull(audio.getLastAudioFocusRequest());
            audio.removeOutputDevice(headphones, true);
            shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(audio.getLastAudioFocusRequest());
            preferences.edit().putBoolean(AlarmPlayer.SPEAKER_ENABLED, false).apply();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Sonido desactivado en altavoz", status.get());
        } finally { player.stop(); }
    }
}
