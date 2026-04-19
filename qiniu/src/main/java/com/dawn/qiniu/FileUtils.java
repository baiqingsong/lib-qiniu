package com.dawn.qiniu;

import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.RandomAccessFile;

class FileUtils {

    private static final String TAG = "FileUtils";
    private static final long MAX_FILE_SIZE = 500 * 1024 * 1024L; // 500MB上限

    public static byte[] readFile(String filePath) {
        if (filePath == null) {
            return null;
        }
        return readFile(new File(filePath));
    }

    /**
     * 读取文件，文件转换成字节数组
     */
    public static byte[] readFile(File file) {
        if (file == null || !file.exists() || !file.canRead()) {
            return null;
        }
        if (file.length() > MAX_FILE_SIZE) {
            Log.e(TAG, "文件过大，超出限制: " + file.length() + " bytes");
            return null;
        }
        if (file.length() == 0) {
            return new byte[0];
        }
        RandomAccessFile rf = null;
        byte[] data = null;
        try {
            rf = new RandomAccessFile(file, "r");
            data = new byte[(int) rf.length()];
            rf.readFully(data);
        } catch (Exception e) {
            Log.e(TAG, "读取文件失败: " + file.getAbsolutePath(), e);
        } finally {
            closeQuietly(rf);
        }
        return data;
    }

    /**
     * 关闭流
     */
    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
