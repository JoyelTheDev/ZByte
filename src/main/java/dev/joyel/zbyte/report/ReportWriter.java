package dev.joyel.zbyte.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

public final class ReportWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ReportWriter() {
    }

    public static File write(RunReport report, File target) throws IOException {
        File file = target.getName().toLowerCase(Locale.ROOT).endsWith(".json") ? target : new File(target.getPath() + ".json");
        writeFile(file, toJson(report));
        return file;
    }

    private static void writeFile(File file, String content) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

    public static String toJson(RunReport report) {
        return GSON.toJson(report);
    }
}
