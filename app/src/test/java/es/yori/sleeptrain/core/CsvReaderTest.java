package es.yori.sleeptrain.core;

import org.junit.Test;
import java.io.StringReader;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.Assert.*;

public class CsvReaderTest {
    @Test public void stripsBomAndRenfePadding() throws Exception {
        try (CsvReader csv = new CsvReader(new StringReader("\ufeffstop_id,stop_name   \r\n18000,Madrid-Atocha Cercanías   \r\n"))) {
            assertEquals(Arrays.asList("stop_id", "stop_name"), csv.read());
            assertEquals(Arrays.asList("18000", "Madrid-Atocha Cercanías"), csv.read()); assertNull(csv.read());
        }
    }
    @Test public void quotedFieldsMayContainCommasQuotesAndNewlines() throws Exception {
        try (CsvReader csv = new CsvReader(new StringReader("1,\"Estación, \"\"A\"\"\nSur\",\n"))) {
            assertEquals(Arrays.asList("1", "Estación, \"A\"\nSur", ""), csv.read());
        }
    }
    @Test public void finalRecordNeedsNoNewline() throws Exception {
        try (CsvReader csv = new CsvReader(new StringReader("a,b,"))) {
            assertEquals(Arrays.asList("a", "b", ""), csv.read()); assertNull(csv.read());
        }
    }
    @Test(expected = IOException.class) public void rejectsUnterminatedQuote() throws Exception {
        try (CsvReader csv = new CsvReader(new StringReader("a,\"b"))) { csv.read(); }
    }
}

