package es.yori.sleeptrain.data;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class HttpClient {
    private HttpClient() { }
    public static byte[] get(String url, int limit, int timeout) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("Se requiere HTTPS");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
        connection.setRequestProperty("User-Agent", "SlipTrain/0.2 Android");
        connection.setRequestProperty("Accept", "application/x-protobuf, application/octet-stream, application/json, application/zip");
        try (InputStream input = connection.getInputStream()) { return read(input, limit); }
        finally { connection.disconnect(); }
    }
    public static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[16 * 1024]; int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > limit) throw new IOException("Respuesta demasiado grande");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
    public static String string(InputStream input, int limit) throws IOException { return new String(read(input, limit), StandardCharsets.UTF_8); }
}
