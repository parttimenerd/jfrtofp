package me.bechberger.jfrtofp;

import me.bechberger.jfrtofp.converter.JFRConverter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.zip.GZIPOutputStream;

@Command(
    name = "jfrtofp",
    mixinStandardHelpOptions = true,
    description = "Converting JFR files to Firefox Profiler profiles"
)
public class Main implements Callable<Integer> {

    @Parameters(index = "0", description = "The JFR or CJFR file to convert")
    Path file;

    @Option(names = {"-o", "--output"}, description = "The output file (.json or .json.gz)")
    Path output;

    @Override
    public Integer call() throws Exception {
        if (output != null
                && !output.toString().endsWith(".json")
                && !output.toString().endsWith(".json.gz")) {
            System.out.println("Output file must end with .json or .json.gz");
            return 1;
        }
        String src = file.toString();
        Path outputFile = output != null ? output
                : Path.of(src.replace(".jfr", ".json.gz").replace(".cjfr", ".json.gz"));

        long t0 = System.currentTimeMillis();
        try (OutputStream raw = Files.newOutputStream(outputFile)) {
            if (outputFile.getFileName().toString().endsWith(".json")) {
                JFRConverter.convertPath(file, raw);
            } else {
                try (GZIPOutputStream gz = new GZIPOutputStream(raw)) {
                    JFRConverter.convertPath(file, gz);
                }
            }
        }
        System.err.println("Took " + (System.currentTimeMillis() - t0) + " ms");
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
