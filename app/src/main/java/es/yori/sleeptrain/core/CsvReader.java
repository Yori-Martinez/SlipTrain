package es.yori.sleeptrain.core;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** Streaming RFC 4180 records, including embedded newlines, escaped quotes and Renfe padding. */
public final class CsvReader implements AutoCloseable {
    private final PushbackReader reader;
    public CsvReader(Reader reader) { this.reader = new PushbackReader(reader, 1); }

    public List<String> read() throws IOException {
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, any = false;
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\ufeff' && !any && row.isEmpty()) continue;
            any = true;
            if (c == '"') {
                if (quoted) {
                    int next = reader.read();
                    if (next == '"') field.append('"');
                    else { quoted = false; if (next != -1) reader.unread(next); }
                } else if (field.toString().trim().isEmpty()) quoted = true;
                else field.append((char) c);
            } else if (c == ',' && !quoted) {
                row.add(field.toString().trim()); field.setLength(0);
            } else if ((c == '\n' || c == '\r') && !quoted) {
                if (c == '\r') { int next = reader.read(); if (next != '\n' && next != -1) reader.unread(next); }
                row.add(field.toString().trim()); return row;
            } else field.append((char) c);
        }
        if (quoted) throw new IOException("CSV con comillas sin cerrar");
        if (!any && field.length() == 0 && row.isEmpty()) return null;
        row.add(field.toString().trim()); return row;
    }
    @Override public void close() throws IOException { reader.close(); }
}

