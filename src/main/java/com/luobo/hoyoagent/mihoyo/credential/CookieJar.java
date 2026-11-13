package com.luobo.hoyoagent.mihoyo.credential;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * cookie 的唯一表示。不可变，所有修改都返回新实例。
 *
 * <p>两个入口别混：
 * <ul>
 *   <li>{@link #of(String)}：解析请求头 {@code Cookie: a=1; b=2}</li>
 *   <li>{@link #ofSetCookies(List)}：解析响应头 {@code Set-Cookie: a=1; Path=/; ...}，
 *       只取第一个 {@code name=value}，{@code Path}/{@code Expires} 一律丢掉</li>
 * </ul>
 */
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

    /** Set-Cookie 不是 Cookie：属性必须剥掉，否则拼回 Cookie 头时会带上垃圾字段。 */
    public static CookieJar ofSetCookies(List<String> setCookieHeaders) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (setCookieHeaders != null) {
            for (String header : setCookieHeaders) {
                String main = header.split(";", 2)[0];
                int i = main.indexOf('=');
                if (i > 0) {
                    parsed.putIfAbsent(main.substring(0, i).trim(), main.substring(i + 1).trim());
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

    public CookieJar merge(CookieJar other) {
        return merge(other.cookies);
    }

    public Optional<String> get(String name) {
        return Optional.ofNullable(cookies.get(name));
    }

    public boolean has(String name) {
        String value = cookies.get(name);
        return value != null && !value.isBlank();
    }

    public boolean isEmpty() {
        return cookies.isEmpty();
    }

    /** 只读视图，给需要按 Map 遍历的调用方。 */
    public Map<String, String> asMap() {
        return cookies;
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
