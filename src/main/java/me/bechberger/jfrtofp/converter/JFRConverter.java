package me.bechberger.jfrtofp.converter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Public entry point for JFR/CJFR → Firefox Profiler JSON conversion.
 * Used by the jfrtofp CLI and the WASM shims.
 */
public final class JFRConverter {

    private JFRConverter() {}

    /**
     * Convert raw JFR or CJFR bytes to Firefox Profiler JSON written to {@code out}.
     *
     * @param bytes     raw bytes of the JFR or CJFR file
     * @param extension ".jfr" or ".cjfr" — determines the parser used
     * @param out       stream to write uncompressed JSON to
     */
    public static void convert(byte[] bytes, String extension, OutputStream out)
            throws Exception {
        Path tmp = Files.createTempFile("jfrtofp-", extension);
        try {
            Files.write(tmp, bytes);
            convertPath(tmp, out);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Convert a JFR or CJFR file at {@code path} to Firefox Profiler JSON using the given config.
     */
    public static void convert(Path path, ConverterConfig config, OutputStream out) throws Exception {
        String name = path.getFileName().toString();
        if (name.endsWith(".cjfr")) {
            CJFRConvert.convert(path, config, out);
        } else {
            JFRConvert.convert(path, config, out);
        }
    }

    /**
     * Convert a JFR or CJFR file at {@code path} to Firefox Profiler JSON with default config.
     */
    public static void convertPath(Path path, OutputStream out) throws Exception {
        convert(path, ConverterConfig.defaults(), out);
    }
}
