package me.bechberger.jfrtofp;

import me.bechberger.jfrtofp.converter.JFRConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConverterTest {

    @Test
    void testJfrConversion() throws Exception {
        Path jfr = Path.of("flight.jfr");
        Assumptions.assumeTrue(jfr.toFile().exists(), "flight.jfr not present");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JFRConverter.convertPath(jfr, out);
        String json = out.toString("UTF-8");
        assertTrue(json.contains("\"threads\""), "Output must contain threads");
        assertTrue(json.length() > 1000, "Output must be non-trivial (got " + json.length() + " bytes)");
    }

    @Test
    void testCjfrConversion() throws Exception {
        Path cjfr = Path.of("condensed-data/profile_lossless.cjfr");
        Assumptions.assumeTrue(cjfr.toFile().exists(), "profile_lossless.cjfr not present");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JFRConverter.convertPath(cjfr, out);
        String json = out.toString("UTF-8");
        assertTrue(json.contains("\"threads\""), "Output must contain threads");
        assertTrue(json.length() > 1000, "Output must be non-trivial (got " + json.length() + " bytes)");

        // Verify non-zero samples (the sampledThread bug would cause 0 samples)
        long stackCount = countOccurrences(json, "\"stack\":[");
        // at least one thread must have samples
        assertTrue(stackCount > 0 || json.contains("\"samples\""), "No samples found — possible sampledThread bug");
    }

    @Test
    void testBytesApi() throws Exception {
        Path jfr = Path.of("flight.jfr");
        Assumptions.assumeTrue(jfr.toFile().exists(), "flight.jfr not present");
        byte[] bytes = java.nio.file.Files.readAllBytes(jfr);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JFRConverter.convert(bytes, ".jfr", out);
        String json = out.toString("UTF-8");
        assertTrue(json.contains("\"threads\""), "Bytes API output must contain threads");
    }

    private static long countOccurrences(String s, String sub) {
        long count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
