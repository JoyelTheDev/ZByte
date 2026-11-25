package dev.joyel.zbyte.report;

import java.util.List;

public record Finding(String rule, String description, String message, List<String> recommended) {
}
