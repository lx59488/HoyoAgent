package com.luobo.hoyoagent.mihoyo.credential;

import java.util.*;

/** cookie 的唯一表示。不可变，合并返回新实例。 */
public final class CookieJar {

    private final Map<String, String> cookies;

    private CookieJar(Map<String, String> cookies) {
        this.cookies = Collections.unmodifiableMap(new LinkedHashMap<>(cookies));
    }

    public static CookieJar empty() {
        return new CookieJar(Map.of());
    }

    public static CookieJar of(String header) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (header != null) {
            for (String part : header.split(";")) {
                String s = part.trim();
                int i = s.indexOf('=');
                if (i > 0) {
                    parsed.putIfAbsent(s.substring(0, i), s.substring(i + 1));
                }
            }
        }
        return new CookieJar(parsed);
    }


    public CookieJar with(String name, String value) {
        if (value == null || value.isBlank()) {
            return this;
        }
        Map<String, String> copy = new LinkedHashMap<>(cookies);
        copy.put(name, value);
        return new CookieJar(copy);
    }

    /** v2 覆盖同名键；v1 不删——实测 v1+v2 并存服务端能正常挑（见 docs/mihoyo-auth.md §2）。 */
    public CookieJar merge(Map<String, String> overrides) {
        CookieJar jar = this;
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            jar = jar.with(e.getKey(), e.getValue());
        }
        return jar;
    }

    public Optional<String> get(String name) {
        return Optional.ofNullable(cookies.get(name));
    }

    public boolean has(String name) {
        String value = cookies.get(name);
        return value != null && !value.isBlank();
    }

    /** 唯一的序列化出口。 */
    public String header() {
        StringJoiner joiner = new StringJoiner("; ");
        cookies.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                joiner.add(k + "=" + v);
            }
        });
        return joiner.toString();
    }
}
