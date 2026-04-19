package com.dawn.qiniu;

import com.qiniu.android.common.FixedZone;
import com.qiniu.android.common.Zone;
import com.qiniu.android.storage.Configuration;
import com.qiniu.android.storage.UploadManager;

/**
 * 初始化七牛管理器
 */
class QiNiuManager {
    private static volatile UploadManager instance;
    private static volatile CustomZone currentZone;

    private QiNiuManager() {
    }

    public static UploadManager getInstance() {
        return getInstance(CustomZone.ZONE_HUA_DONG);
    }

    /**
     * 获取UploadManager，zone变化时自动重建
     *
     * @param customZone 上传区域
     */
    public static UploadManager getInstance(CustomZone customZone) {
        if (customZone == null) {
            customZone = CustomZone.ZONE_HUA_DONG;
        }
        if (instance == null || currentZone != customZone) {
            synchronized (QiNiuManager.class) {
                if (instance == null || currentZone != customZone) {
                    currentZone = customZone;
                    instance = new UploadManager(buildConfig(customZone));
                }
            }
        }
        return instance;
    }

    static void reset() {
        synchronized (QiNiuManager.class) {
            instance = null;
            currentZone = null;
        }
    }

    private static Configuration buildConfig(CustomZone customZone) {
        Zone zone;
        switch (customZone) {
            case ZONE_HUA_BEI:
                zone = FixedZone.zone1;
                break;
            case ZONE_HUA_NAN:
                zone = FixedZone.zone2;
                break;
            case ZONE_BEI_MEI:
                zone = FixedZone.zoneNa0;
                break;
            case ZONE_DONG_NAN_YA:
                zone = FixedZone.zoneAs0;
                break;
            case ZONE_HUA_DONG:
            default:
                zone = FixedZone.zone0;
                break;
        }
        return new Configuration.Builder()
                .connectTimeout(30)              // 链接超时，30秒
                .useHttps(true)                  // 使用HTTPS上传
                .useConcurrentResumeUpload(true) // 使用并发上传
                .concurrentTaskCount(3)          // 并发上传线程数量为3
                .responseTimeout(60)             // 服务器响应超时，60秒
                .zone(zone)
                .build();
    }
}
