package com.luobo.hoyoagent.mihoyo.credential;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 {@code key=value} 文件读写。
 *
 * <p>不用 {@link java.util.Properties}：它会把 {@code cookie} 里的 {@code =}、空格转义成
 * 肉眼难读的形式；我们自己写、自己读，保持文件可人工检查即可。
 */
final class PropertyFile {

    private PropertyFile() {
    }

    static Map<String, String> read(Path file) throws IOException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    static Map<String, String> parse(List<String> lines) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int i = trimmed.indexOf('=');
            if (i > 0) {
                kv.put(trimmed.substring(0, i).trim(), trimmed.substring(i + 1).trim());
            }
        }
        return kv;
    }

    /** 空串当不存在，避免把 {@code stoken=} 写成一个空 token。 */
    static String orNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
