package me.bechberger.jfrtofp;

import java.io.ByteArrayOutputStream;
import me.bechberger.jfrtofp.converter.JFRConverter;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;

/**
 * GraalVM Web Image entry point for .cjfr files.
 * Thin shim — all conversion logic is in JFRConverter.
 */
public final class CJFRParser {

    public static String parseToProfileJSON(JSObject wrapped) {
        try {
            String binaryString = getStringProperty(wrapped, "value");
            int len = binaryString.length();
            byte[] bytes = new byte[len];
            for (int i = 0; i < len; i++) bytes[i] = (byte) binaryString.charAt(i);
            binaryString = null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream(1 << 20);
            JFRConverter.convert(bytes, ".cjfr", baos);
            return baos.toString("UTF-8");
        } catch (Throwable t) {
            t.printStackTrace();
            throw new RuntimeException("CJFRParser.parseToProfileJSON failed: " + t.getMessage(), t);
        }
    }

    @FunctionalInterface
    interface ParseHandler {
        String parse(JSObject wrapped);
    }

    public static void register() {
        attachToGlobal(CJFRParser::parseToProfileJSON);
    }

    @JS.Coerce
    @JS("""
            globalThis.CJFRParser = {
                parseToProfileJSON: (bs) => {
                    var r = parseHandler.parse({ value: bs });
                    return (typeof r === 'string') ? r : (r == null ? '' : String(r));
                }
            };
            """)
    private static native void attachToGlobal(ParseHandler parseHandler);

    @JS.Coerce
    @JS("return obj[prop];")
    private static native String getStringProperty(JSObject obj, String prop);
}
