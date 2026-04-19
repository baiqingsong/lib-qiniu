package com.dawn.qiniu;

import android.graphics.Bitmap;

import com.qiniu.android.utils.StringMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

class StringUtils {
    private StringUtils() {
    }

    /**
     * @see #join(Object[] array, String sep, String prefix)
     */
    public static String join(Object[] array, String sep) {
        return join(array, sep, null);
    }

    /**
     * @see #join(Object[] array, String sep, String prefix)
     */
    public static String join(Collection list, String sep) {
        return join(list, sep, null);
    }

    /**
     * @see #join(Object[] array, String sep, String prefix)
     */
    public static String join(Collection list, String sep, String prefix) {
        Object[] array = list == null ? null : list.toArray();
        return join(array, sep, prefix);
    }

    /**
     * 以指定的分隔符来进行字符串元素连接
     *
     * @param array  需要连接的对象数组
     * @param sep    元素连接之间的分隔符
     * @param prefix 前缀字符串
     * @return 连接好的新字符串
     */
    public static String join(Object[] array, String sep, String prefix) {
        if (array == null || array.length == 0) {
            return "";
        }

        if (sep == null) {
            sep = "";
        }

        if (prefix == null) {
            prefix = "";
        }

        StringBuilder buf = new StringBuilder(prefix);
        for (int i = 0; i < array.length; i++) {
            if (i > 0) {
                buf.append(sep);
            }
            buf.append(array[i] == null ? "" : array[i]);
        }
        return buf.toString();
    }

    public static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public static boolean inStringArray(String s, String[] array) {
        if (s == null || array == null) {
            return false;
        }
        for (String x : array) {
            if (x.equals(s)) {
                return true;
            }
        }
        return false;
    }

    public static byte[] utf8Bytes(String data) {
        return data.getBytes(StandardCharsets.UTF_8);
    }

    public static String encode(StringMap map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.map().entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static byte[] bitmapToBytes(Bitmap bm) {
        return bitmapToBytes(bm, 100);
    }

    public static byte[] bitmapToBytes(Bitmap bm, int quality) {
        if (bm == null || bm.isRecycled()) {
            return null;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            bm.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            return baos.toByteArray();
        } finally {
            try {
                baos.close();
            } catch (IOException ignored) {
            }
        }
    }
}
