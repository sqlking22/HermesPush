package com.hermes.push.storage;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import java.util.regex.Pattern;

/** 逻辑 URI：hp://{storageKey}/{path}。入库只存此形式，不存物理绝对路径（ST-2）。 */
public final class LogicalUri {
    public static final String SCHEME = "hp";
    private static final Pattern STORAGE_KEY = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final Pattern SEGMENT = Pattern.compile("[a-zA-Z0-9_.-]+");

    private final String storageKey;
    private final String path;

    private LogicalUri(String storageKey, String path) {
        this.storageKey = storageKey;
        this.path = path;
    }

    public static LogicalUri of(String storageKey, String path) {
        return new LogicalUri(validateKey(storageKey), validatePath(path));
    }

    public static LogicalUri parse(String uri) {
        if (uri == null || !uri.startsWith(SCHEME + "://")) {
            throw new BizException(ErrorCode.STO_003, "非法 URI: " + uri);
        }
        String rest = uri.substring((SCHEME + "://").length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new BizException(ErrorCode.STO_003, "非法 URI: " + uri);
        }
        return new LogicalUri(validateKey(rest.substring(0, slash)),
                validatePath(rest.substring(slash + 1)));
    }

    private static String validateKey(String key) {
        if (key == null || !STORAGE_KEY.matcher(key).matches()) {
            throw new BizException(ErrorCode.STO_003, "非法存储键: " + key);
        }
        return key;
    }

    private static String validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new BizException(ErrorCode.STO_003, "路径为空");
        }
        String p = path.replace('\\', '/');
        if (p.startsWith("/")) {
            throw new BizException(ErrorCode.STO_003, "非法绝对路径: " + path);
        }
        for (String seg : p.split("/", -1)) {
            if (seg.isBlank() || "..".equals(seg) || !SEGMENT.matcher(seg).matches()) {
                throw new BizException(ErrorCode.STO_003, "非法路径段: " + path);
            }
        }
        return p;
    }

    public String storageKey() { return storageKey; }
    public String path() { return path; }
    @Override public String toString() { return SCHEME + "://" + storageKey + "/" + path; }
}
