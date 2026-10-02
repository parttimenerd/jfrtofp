package me.bechberger.jfrtofp.converter;

import java.util.HashMap;

/** Lightweight snapshot of a parsed event stored in the prebuffer. */
public final class PrebufferedEvent {
    public String typeName;
    public double startMs;
    public double endMs;
    public HashMap<String, Object> fields;
    public Processor.JFRThread thread;
    public int stackDepth;
    public String[] frameClassNames;
    public String[] frameMethodNames;
    public String[] frameDescriptors;
    public int[] frameLineNumbers;
    public boolean[] frameIsJava;
}
