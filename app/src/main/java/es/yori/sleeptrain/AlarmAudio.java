package es.yori.sleeptrain;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.regex.Pattern;

/** Keeps the chosen audio available offline and after the document provider revokes its grant. */
final class AlarmAudio {
    static final String SESSION_KEY = "audioId";
    static final String DEFAULT_NAME = "Tono de SlipTrain";
    static final String SECRET_NAME = "Tono secreto";
    static final String SECRET_IMPORT_NAME = "Tono secreto importado";
    private static final String BUNDLED_SECRET_ID = "keyboard-cat";
    private static final String SECRET_UNLOCKED = "bundledSecretUnlocked";
    private static final String DIRECTORY = "alarm_audio";
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final Pattern ID = Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");

    static final class Choice {
        public final String id;
        public final String name;

        Choice(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private AlarmAudio() { }

    static Intent pickerIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    /** Must run off the main thread. */
    static Choice importAudio(Context context, Uri uri) throws IOException {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new IOException("No se pudo abrir el archivo");
        }
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("No se pudo guardar el sonido");
        }
        String id = UUID.randomUUID().toString();
        File temporary = File.createTempFile("import-", ".part", directory);
        File destination = audioFile(context, id);
        boolean saved = false;
        try {
            ContentResolver resolver = context.getContentResolver();
            String name = displayName(resolver, uri);
            try (InputStream input = resolver.openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IOException("No se pudo abrir el archivo");
                byte[] buffer = new byte[32 * 1024];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES) throw new IOException("El archivo supera los 64 MB");
                    output.write(buffer, 0, count);
                }
                if (total == 0) throw new IOException("El archivo está vacío");
            }
            requireAudioTrack(temporary);
            if (!temporary.renameTo(destination) || !context.getSharedPreferences(DIRECTORY, Context.MODE_PRIVATE)
                    .edit().putString(id, name).commit()) {
                throw new IOException("No se pudo guardar el sonido");
            }
            saved = true;
            return new Choice(id, name);
        } catch (RuntimeException error) {
            throw new IOException("No se pudo importar el sonido", error);
        } finally {
            temporary.delete();
            if (!saved) destination.delete();
        }
    }

    static String name(Context context, String id) {
        if (isBundledSecret(id)) return SECRET_NAME;
        if (file(context, id) == null) return DEFAULT_NAME;
        if (id.equals(importedSecretTone(context))) return SECRET_IMPORT_NAME;
        return context.getSharedPreferences(DIRECTORY, Context.MODE_PRIVATE).getString(id, "Sonido personalizado");
    }

    static String secretTone(Context context) {
        return context.getSharedPreferences(DIRECTORY, Context.MODE_PRIVATE).getBoolean(SECRET_UNLOCKED, false)
                ? BUNDLED_SECRET_ID : "";
    }

    static String importedSecretTone(Context context) {
        String id = context.getSharedPreferences(DIRECTORY, Context.MODE_PRIVATE).getString("secretToneId", "");
        return file(context, id) == null ? "" : id;
    }

    static String unlockSecretTone(Context context) {
        context.getSharedPreferences(DIRECTORY, Context.MODE_PRIVATE).edit().putBoolean(SECRET_UNLOCKED, true).apply();
        return BUNDLED_SECRET_ID;
    }

    static boolean isBundledSecret(String id) { return BUNDLED_SECRET_ID.equals(id); }

    static File file(Context context, String id) {
        if (id == null || !ID.matcher(id).matches()) return null;
        File file = audioFile(context, id);
        return file.isFile() ? file : null;
    }

    private static File directory(Context context) { return new File(context.getFilesDir(), DIRECTORY); }
    private static File audioFile(Context context, String id) { return new File(directory(context), id + ".audio"); }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor result = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (result != null && result.moveToFirst()) {
                int column = result.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                String name = column < 0 ? null : result.getString(column);
                if (name != null && !name.trim().isEmpty()) return name.trim();
            }
        } catch (RuntimeException ignored) { /* Some providers cannot return metadata. */ }
        return "Sonido personalizado";
    }

    private static void requireAudioTrack(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try (FileInputStream input = new FileInputStream(file)) {
            extractor.setDataSource(input.getFD());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) return;
            }
            throw new IOException("El archivo no contiene audio compatible");
        } finally {
            extractor.release();
        }
    }
}
