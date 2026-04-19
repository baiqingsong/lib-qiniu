package com.dawn.qiniu;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.storage.UpCompletionHandler;
import com.qiniu.android.storage.UploadManager;
import com.qiniu.android.storage.UploadOptions;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QiNiuFactory {

    private static final String TAG = "QiNiuFactory";
    private static final long TOKEN_EXPIRE_SECONDS = 3600;
    private static final long TOKEN_REFRESH_BUFFER = 300;
    private static final int DEFAULT_MAX_RETRY = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 2000;
    private static final int MAX_BACKOFF_SHIFT = 4; // 最大退避指数，2^4=16倍

    private static volatile QiNiuFactory instance;

    private final String access;
    private final String secret;
    private final String bucket;
    private final String host;
    private final UploadManager uploadManager;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private final Object tokenLock = new Object();
    private long tokenExpireTime;
    private String token;
    private final QiNiuUtil qiNiuUtil;

    private int maxRetryCount = DEFAULT_MAX_RETRY;
    private long retryDelayMs = DEFAULT_RETRY_DELAY_MS;
    private volatile boolean isDestroyed = false;

    private QiNiuFactory(String access, String secret, String bucket, String host, CustomZone customZone) {
        if (TextUtils.isEmpty(access) || TextUtils.isEmpty(secret)
                || TextUtils.isEmpty(bucket) || TextUtils.isEmpty(host)) {
            throw new IllegalArgumentException("access, secret, bucket, host must not be empty");
        }
        this.access = access;
        this.secret = secret;
        this.bucket = bucket;
        this.host = host.endsWith("/") ? host : host + "/";
        this.qiNiuUtil = QiNiuUtil.create(access, secret);
        this.uploadManager = QiNiuManager.getInstance(customZone);
        this.executor = Executors.newFixedThreadPool(3);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static QiNiuFactory getInstance(String access, String secret, String bucket, String host, CustomZone customZone) {
        if (instance == null) {
            synchronized (QiNiuFactory.class) {
                if (instance == null) {
                    instance = new QiNiuFactory(access, secret, bucket, host, customZone);
                }
            }
        }
        return instance;
    }

    public static QiNiuFactory getInstance(String access, String secret, String bucket, String host) {
        return getInstance(access, secret, bucket, host, CustomZone.ZONE_HUA_DONG);
    }

    /**
     * 销毁实例，释放资源，取消未执行的上传任务
     */
    public static synchronized void destroy() {
        if (instance != null) {
            instance.isDestroyed = true;
            instance.executor.shutdownNow();
            instance = null;
            QiNiuManager.reset();
        }
    }

    /**
     * 获取上传凭证
     */
    private String getUpToken() {
        return qiNiuUtil.uploadToken(bucket);
    }

    /**
     * 检查并获取有效token（线程安全）
     */
    private String checkUpToken() {
        synchronized (tokenLock) {
            long now = System.currentTimeMillis() / 1000;
            if (token == null || now >= tokenExpireTime - TOKEN_REFRESH_BUFFER) {
                token = getUpToken();
                tokenExpireTime = now + TOKEN_EXPIRE_SECONDS;
            }
            return token;
        }
    }

    /**
     * 强制刷新token（401时调用）
     */
    private void forceRefreshToken() {
        synchronized (tokenLock) {
            tokenExpireTime = 0;
            token = null;
        }
    }

    /**
     * 设置重试配置
     *
     * @param maxRetryCount 最大重试次数，0表示不重试，默认3次
     * @param retryDelayMs  首次重试延迟（毫秒），后续按指数退避递增，默认2000ms
     */
    public void setRetryConfig(int maxRetryCount, long retryDelayMs) {
        this.maxRetryCount = Math.max(0, maxRetryCount);
        this.retryDelayMs = Math.max(0, retryDelayMs);
    }

    /**
     * 获取当前最大重试次数
     */
    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    /**
     * 获取当前重试延迟（毫秒）
     */
    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    // ==================== 文件上传 ====================

    /**
     * 文件上传
     *
     * @param fileName 本地文件路径
     * @param key      七牛云上传路径，包括文件夹和文件名称
     * @param listener 上传监听
     */
    public void uploadFile(String fileName, String key, QiNiuUploadListener listener) {
        uploadFile(null, fileName, key, listener);
    }

    /**
     * 文件上传（直接传File给SDK，避免大文件全部读入内存）
     *
     * @param token    七牛云token值，为空时自动获取
     * @param fileName 本地文件路径
     * @param key      七牛云上传路径，包括文件夹和文件名称
     * @param listener 上传监听
     */
    public void uploadFile(String token, String fileName, String key, QiNiuUploadListener listener) {
        if (TextUtils.isEmpty(fileName)) {
            notifyFail(key, "文件路径为空", listener);
            return;
        }
        File file = new File(fileName);
        if (!file.exists()) {
            notifyFail(key, "文件不存在: " + fileName, listener);
            return;
        }
        if (!file.canRead()) {
            notifyFail(key, "文件不可读: " + fileName, listener);
            return;
        }
        if (file.length() == 0) {
            notifyFail(key, "文件为空: " + fileName, listener);
            return;
        }
        if (TextUtils.isEmpty(key)) {
            notifyFail(key, "上传key为空", listener);
            return;
        }
        doUpload(token, key,
                (uploadToken, handler, options) -> uploadManager.put(file, key, uploadToken, handler, options),
                maxRetryCount, maxRetryCount, listener);
    }

    // ==================== 图片上传 ====================

    /**
     * 图片上传（JPEG quality=100）
     *
     * @param bitmap   图片
     * @param key      七牛云上传路径，包括文件夹和文件名称
     * @param listener 上传监听
     */
    public void uploadImage(Bitmap bitmap, String key, QiNiuUploadListener listener) {
        uploadImage(null, bitmap, key, 100, listener);
    }

    /**
     * 图片上传（自定义token，JPEG quality=100）
     *
     * @param token    七牛云token值，为空时自动获取
     * @param bitmap   图片
     * @param key      七牛云上传路径，包括文件夹和文件名称
     * @param listener 上传监听
     */
    public void uploadImage(String token, Bitmap bitmap, String key, QiNiuUploadListener listener) {
        uploadImage(token, bitmap, key, 100, listener);
    }

    /**
     * 图片上传（自定义JPEG压缩质量）
     *
     * @param bitmap   图片
     * @param key      七牛云上传路径
     * @param quality  JPEG压缩质量，1-100
     * @param listener 上传监听
     */
    public void uploadImage(Bitmap bitmap, String key, int quality, QiNiuUploadListener listener) {
        uploadImage(null, bitmap, key, quality, listener);
    }

    /**
     * 图片上传（自定义token + 自定义JPEG压缩质量）
     *
     * @param token    七牛云token值，为空时自动获取
     * @param bitmap   图片
     * @param key      七牛云上传路径
     * @param quality  JPEG压缩质量，1-100
     * @param listener 上传监听
     */
    public void uploadImage(String token, Bitmap bitmap, String key, int quality, QiNiuUploadListener listener) {
        if (bitmap == null || bitmap.isRecycled()) {
            notifyFail(key, "Bitmap为空或已回收", listener);
            return;
        }
        int q = Math.max(1, Math.min(100, quality));
        byte[] data = StringUtils.bitmapToBytes(bitmap, q);
        if (data == null || data.length == 0) {
            notifyFail(key, "Bitmap转换失败", listener);
            return;
        }
        uploadData(token, data, key, listener);
    }

    // ==================== 字节数组上传 ====================

    /**
     * 字节数组上传
     *
     * @param data     文件数据
     * @param key      七牛云上传路径，包括文件夹和文件名称
     * @param listener 上传监听
     */
    public void uploadData(byte[] data, String key, QiNiuUploadListener listener) {
        uploadData(null, data, key, listener);
    }

    /**
     * 字节数组上传
     *
     * @param token    七牛云token值，为空时自动获取
     * @param data     文件数据
     * @param key      七牛云上传路径，包括文件夹和文件名称
     * @param listener 上传监听
     */
    public void uploadData(String token, byte[] data, String key, QiNiuUploadListener listener) {
        if (data == null || data.length == 0) {
            notifyFail(key, "上传数据为空", listener);
            return;
        }
        if (TextUtils.isEmpty(key)) {
            notifyFail(key, "上传key为空", listener);
            return;
        }
        doUpload(token, key,
                (uploadToken, handler, options) -> uploadManager.put(data, key, uploadToken, handler, options),
                maxRetryCount, maxRetryCount, listener);
    }

    // ==================== 统一上传（带重试） ====================

    /**
     * 执行上传，支持失败自动重试
     *
     * @param customToken 自定义token，null表示自动获取
     * @param key         上传key
     * @param action      实际上传操作
     * @param retriesLeft 剩余重试次数
     * @param totalRetry  总重试次数
     * @param listener    回调
     */
    private void doUpload(String customToken, String key, UploadAction action,
                          int retriesLeft, int totalRetry, QiNiuUploadListener listener) {
        final UpCompletionHandler handler = (k, info, response) -> {
            String url = host + k;
            if (info.isOK()) {
                notifySuccess(url, listener);
                return;
            }

            // 判断是否可重试
            boolean canRetry = retriesLeft > 0 && !isDestroyed && isRetryableError(info);
            // 自定义token遇到401无法刷新，不重试
            if (canRetry && info.statusCode == 401 && !TextUtils.isEmpty(customToken)) {
                canRetry = false;
            }

            if (canRetry) {
                int attempt = totalRetry - retriesLeft + 1;
                notifyRetry(attempt, totalRetry, info.error, listener);
                // 401时强制刷新token
                if (info.statusCode == 401) {
                    forceRefreshToken();
                }
                long delay = calculateRetryDelay(attempt);
                mainHandler.postDelayed(() -> {
                    if (!isDestroyed) {
                        doUpload(customToken, key, action, retriesLeft - 1, totalRetry, listener);
                    }
                }, delay);
            } else {
                notifyFail(url, "上传失败: " + info.error, listener);
            }
        };

        final UploadOptions options = new UploadOptions(null, null, false,
                (k, percent) -> notifyProgress((float) percent, listener),
                () -> isDestroyed);

        try {
            String uploadToken = TextUtils.isEmpty(customToken) ? checkUpToken() : customToken;
            executor.execute(() -> {
                try {
                    action.execute(uploadToken, handler, options);
                } catch (Exception e) {
                    Log.e(TAG, "上传异常", e);
                    if (retriesLeft > 0 && !isDestroyed) {
                        int attempt = totalRetry - retriesLeft + 1;
                        notifyRetry(attempt, totalRetry, e.getMessage(), listener);
                        long delay = calculateRetryDelay(attempt);
                        mainHandler.postDelayed(() -> {
                            if (!isDestroyed) {
                                doUpload(customToken, key, action, retriesLeft - 1, totalRetry, listener);
                            }
                        }, delay);
                    } else {
                        notifyFail(host + key, "上传异常: " + e.getMessage(), listener);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "提交上传任务异常", e);
            notifyFail(host + key, "提交上传任务异常: " + e.getMessage(), listener);
        }
    }

    // ==================== 重试策略 ====================

    /**
     * 判断是否为可重试的错误
     */
    private boolean isRetryableError(ResponseInfo info) {
        if (info.isOK()) return false;
        int code = info.statusCode;
        if (code < 0) return true;         // 网络中断（SDK用负数表示客户端错误）
        if (code == 401) return true;       // Token过期
        if (code == 408) return true;       // 请求超时
        if (code == 429) return true;       // 频率限制
        return code >= 500 && code < 600;   // 服务端错误
    }

    /**
     * 计算重试延迟（指数退避）
     * attempt=1: retryDelayMs, attempt=2: retryDelayMs*2, attempt=3: retryDelayMs*4 ...
     */
    private long calculateRetryDelay(int attempt) {
        return retryDelayMs * (1L << Math.min(attempt - 1, MAX_BACKOFF_SHIFT));
    }

    // ==================== 回调通知 ====================

    private void notifySuccess(String url, QiNiuUploadListener listener) {
        if (listener != null) {
            mainHandler.post(() -> listener.uploadSuccess(url));
        }
    }

    private void notifyFail(String key, String error, QiNiuUploadListener listener) {
        if (listener != null) {
            mainHandler.post(() -> listener.uploadFail(key, error));
        }
    }

    private void notifyProgress(float percent, QiNiuUploadListener listener) {
        if (listener != null) {
            mainHandler.post(() -> listener.uploadPercent(percent));
        }
    }

    private void notifyRetry(int currentAttempt, int maxAttempt, String error, QiNiuUploadListener listener) {
        Log.w(TAG, "上传失败，第" + currentAttempt + "/" + maxAttempt + "次重试: " + error);
        if (listener != null) {
            mainHandler.post(() -> listener.uploadRetry(currentAttempt, maxAttempt, error));
        }
    }

    // ==================== 内部接口 ====================

    @FunctionalInterface
    private interface UploadAction {
        void execute(String token, UpCompletionHandler handler, UploadOptions options) throws Exception;
    }

    /**
     * 上传回调监听，所有回调均在主线程执行
     */
    public interface QiNiuUploadListener {
        /** 上传进度回调，percent 范围 0.0 ~ 1.0 */
        void uploadPercent(float percent);

        /** 上传成功，url 为完整的文件访问地址（host + key） */
        void uploadSuccess(String url);

        /** 上传最终失败（重试耗尽后） */
        void uploadFail(String url, String error);

        /**
         * 上传失败即将重试（可选实现）
         *
         * @param currentAttempt 当前第几次重试（从1开始）
         * @param maxAttempt     最大重试次数
         * @param error          本次失败的错误描述
         */
        default void uploadRetry(int currentAttempt, int maxAttempt, String error) {}
    }
}
