package org.graalvm.webimage.api;

/**
 * Stub for GraalVM Web Image JSObject.
 * The real implementation is provided by the GraalVM --tool:svm-wasm build toolchain.
 */
public class JSObject {
    public Object getMember(String name) {
        throw new UnsupportedOperationException("JSObject stub — only valid in WASM runtime");
    }
}
