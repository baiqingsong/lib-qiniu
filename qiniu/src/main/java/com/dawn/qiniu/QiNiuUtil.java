package com.dawn.qiniu;

import android.util.Log;

import com.qiniu.android.utils.StringMap;
import com.qiniu.android.utils.UrlSafeBase64;

import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

class QiNiuUtil {

    private static final String TAG = "QiNiuUtil";

    /**
     * 上传策略字段
     * http://developer.qiniu.com/docs/v6/api/reference/security/put-policy.html
     */
    private static final String[] policyFields = new String[]{
            "callbackUrl",
            "callbackBody",
            "callbackHost",
            "callbackBodyType",
            "callbackFetchKey",

            "returnUrl",
            "returnBody",

            "endUser",
            "saveKey",
            "insertOnly",

            "detectMime",
            "mimeLimit",
            "fsizeLimit",
            "fsizeMin",

            "persistentOps",
            "persistentNotifyUrl",
            "persistentPipeline",
    };

    private static final String[] deprecatedPolicyFields = new String[]{
            "asyncOps",
    };

    private final String accessKey;
    private final SecretKeySpec secretKey;

    private QiNiuUtil(String accessKey, SecretKeySpec secretKeySpec) {
        this.accessKey = accessKey;
        this.secretKey = secretKeySpec;
    }

    public static QiNiuUtil create(String accessKey, String secretKey) {
        if (StringUtils.isNullOrEmpty(accessKey) || StringUtils.isNullOrEmpty(secretKey)) {
            throw new IllegalArgumentException("accessKey and secretKey must not be empty");
        }
        byte[] sk = StringUtils.utf8Bytes(secretKey);
        SecretKeySpec secretKeySpec = new SecretKeySpec(sk, "HmacSHA1");
        return new QiNiuUtil(accessKey, secretKeySpec);
    }

    /**
     * 生成上传token（默认有效期3600秒）
     *
     * @param bucket 空间名
     * @return 生成的上传token
     */
    public String uploadToken(String bucket) {
        if (StringUtils.isNullOrEmpty(bucket)) {
            throw new IllegalArgumentException("bucket must not be empty");
        }
        return uploadToken(bucket, null, 3600, null, true);
    }

    /**
     * 生成上传token
     *
     * @param bucket  空间名
     * @param key     key，可为 null
     * @param expires 有效时长，单位秒
     * @param policy  上传策略的其它参数
     * @param strict  是否去除非限定的策略字段
     * @return 生成的上传token
     */
    public String uploadToken(String bucket, String key, long expires, StringMap policy, boolean strict) {
        if (expires <= 0) {
            throw new IllegalArgumentException("expires must be positive");
        }
        long deadline = System.currentTimeMillis() / 1000 + expires;
        return uploadTokenWithDeadline(bucket, key, deadline, policy, strict);
    }

    String uploadTokenWithDeadline(String bucket, String key, long deadline, StringMap policy, boolean strict) {
        String scope = bucket;
        if (key != null) {
            scope = bucket + ":" + key;
        }
        StringMap x = new StringMap();
        copyPolicy(x, policy, strict);
        x.put("scope", scope);
        x.put("deadline", deadline);

        String s = StringUtils.encode(x);
        return signWithData(StringUtils.utf8Bytes(s));
    }

    String signWithData(byte[] data) {
        String s = UrlSafeBase64.encodeToString(data);
        return sign(StringUtils.utf8Bytes(s)) + ":" + s;
    }

    private static void copyPolicy(final StringMap policy, StringMap originPolicy, final boolean strict) {
        if (originPolicy == null) {
            return;
        }
        originPolicy.forEach((key, value) -> {
            if (StringUtils.inStringArray(key, deprecatedPolicyFields)) {
                throw new IllegalArgumentException(key + " is deprecated!");
            }
            if (!strict || StringUtils.inStringArray(key, policyFields)) {
                policy.put(key, value);
            }
        });
    }

    String sign(byte[] data) {
        Mac mac = createMac();
        String encodedSign = UrlSafeBase64.encodeToString(mac.doFinal(data));
        return this.accessKey + ":" + encodedSign;
    }

    private Mac createMac() {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(secretKey);
            return mac;
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "创建Mac失败", e);
            throw new IllegalArgumentException(e);
        }
    }
}
