package me.bechberger.jfrtofp;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;

/**
 * GraalVM Web Image entry point.
 * Registers JFRParser and CJFRParser on globalThis so firefox-profiler can call them.
 */
public class WebMain {

    public static void main(String[] args) {
        JFRParser.register();
        CJFRParser.register();
    }
}
