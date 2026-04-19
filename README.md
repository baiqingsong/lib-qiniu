# lib-qiniu

七牛云文件上传 Android 封装库，基于七牛官方 SDK 二次封装，提供简洁易用的上传接口。

## 特性

- 支持文件路径上传（大文件友好，直接传 File 给 SDK，不全部读入内存）
- 支持 Bitmap 图片上传（可自定义 JPEG 压缩质量 1-100）
- 支持 byte[] 字节数组上传
- 支持自定义上传区域（华东、华北、华南、北美、东南亚）
- 支持自定义 token / 自动生成 token（带缓存和自动刷新）
- **失败自动重试**（默认 3 次，指数退避延迟，401 自动刷新 token）
- 上传进度回调，自动切回主线程
- 线程池复用，HTTPS 安全传输，并发分片上传
- destroy() 后自动取消未完成上传和待重试任务
- 无额外 JSON 库依赖（内部手动序列化）

## 环境要求

- Android minSdk 28+
- Java 8+
- Gradle 7.6 + AGP 7.4.2

## 引用

### JitPack 引用

根 `build.gradle` 添加仓库：

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url "https://jitpack.io" }
    }
}
```

模块 `build.gradle` 添加依赖：

```groovy
dependencies {
    implementation 'com.github.baiqingsong:lib-qiniu:Tag'
    // 七牛 SDK 运行时需要 okhttp，以下二选一：
    // 方式1：如果已引用 lib-network，okhttp 由 lib-network 提供，无需重复添加
    // 方式2：如果未引用 lib-network，需手动添加 okhttp
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
```

**与 lib-network 一起使用（推荐）：**

```groovy
dependencies {
    implementation 'com.github.baiqingsong:lib-qiniu:Tag'
    implementation 'com.github.baiqingsong:lib-network:Tag'
    // okhttp 由 lib-network 提供，无需单独添加
}
```

### 本地模块引用

```groovy
dependencies {
    implementation project(':qiniu')
    // okhttp 由 lib-network 或宿主 app 提供
    // 如果未引用 lib-network，需手动添加：
    // implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
```

### 权限配置

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
```

### 混淆规则（如启用 ProGuard）

```proguard
-keep class com.dawn.qiniu.** { *; }
-keep class com.qiniu.android.** { *; }
-dontwarn com.qiniu.**
```

---

## 类说明

### QiNiuFactory（核心类）

七牛云上传工厂类，采用单例模式。提供文件、图片、字节数组三种上传方式，内部自动管理 token 缓存与刷新，上传任务通过线程池异步执行，所有回调自动切回主线程。

### CustomZone（区域枚举）

上传区域枚举，用于指定七牛云存储区域：

| 枚举值 | 区域 |
|--------|------|
| `ZONE_HUA_DONG` | 华东-浙江（默认） |
| `ZONE_HUA_BEI` | 华北-河北 |
| `ZONE_HUA_NAN` | 华南-广东 |
| `ZONE_BEI_MEI` | 北美-洛杉矶 |
| `ZONE_DONG_NAN_YA` | 亚太-新加坡（东南亚） |

### QiNiuFactory.QiNiuUploadListener（回调接口）

上传监听回调接口，所有方法均在**主线程**回调：

| 方法 | 说明 |
|------|------|
| `uploadPercent(float percent)` | 上传进度回调，percent 范围 0.0 ~ 1.0 |
| `uploadSuccess(String url)` | 上传成功，url 为完整的文件访问地址（host + key） |
| `uploadFail(String url, String error)` | 上传最终失败（重试耗尽后），url 为目标地址，error 为错误描述 |
| `uploadRetry(int current, int max, String error)` | 上传失败即将重试（可选实现，default 空方法），current 为第几次重试 |

---

## API 详细说明

### 初始化

#### `getInstance(String access, String secret, String bucket, String host)`

使用默认区域（华东）获取实例。

| 参数 | 类型 | 说明 |
|------|------|------|
| access | String | 七牛云 AccessKey |
| secret | String | 七牛云 SecretKey |
| bucket | String | 存储空间名称 |
| host | String | CDN 域名，如 `https://cdn.example.com` |

#### `getInstance(String access, String secret, String bucket, String host, CustomZone customZone)`

指定区域获取实例。

| 参数 | 类型 | 说明 |
|------|------|------|
| customZone | CustomZone | 上传区域枚举值 |

#### `destroy()`

销毁实例，释放线程池资源，取消未执行的上传任务和待重试任务。建议在 Application 退出或不再需要上传时调用。

#### `setRetryConfig(int maxRetryCount, long retryDelayMs)`

设置重试配置。

| 参数 | 类型 | 说明 |
|------|------|------|
| maxRetryCount | int | 最大重试次数，0 表示不重试，默认 3 |
| retryDelayMs | long | 首次重试延迟（毫秒），后续按指数退避递增，默认 2000ms |

**重试策略说明：**
- 指数退避延迟：第1次=2s，第2次=4s，第3次=8s（最大 16 倍）
- 可重试的错误类型：网络中断、服务端错误 (5xx)、请求超时 (408)、频率限制 (429)、Token 过期 (401)
- Token 过期 (401) 时自动刷新 token 后重试
- 客户端参数错误 (4xx) 不重试，直接回调 `uploadFail`
- 自定义 token 遇到 401 不重试（无法自动刷新）

#### `getMaxRetryCount()` / `getRetryDelayMs()`

获取当前重试配置。

---

### 文件上传

#### `uploadFile(String fileName, String key, QiNiuUploadListener listener)`

通过本地文件路径上传，token 自动获取。大文件直接传 File 给 SDK，不会全部读入内存。

| 参数 | 类型 | 说明 |
|------|------|------|
| fileName | String | 本地文件绝对路径 |
| key | String | 七牛云上传路径（含文件夹和文件名），如 `img/photo.jpg` |
| listener | QiNiuUploadListener | 上传回调监听 |

#### `uploadFile(String token, String fileName, String key, QiNiuUploadListener listener)`

通过本地文件路径上传，使用自定义 token。

| 参数 | 类型 | 说明 |
|------|------|------|
| token | String | 自定义上传 token，传 null 则自动获取 |

---

### 图片上传

#### `uploadImage(Bitmap bitmap, String key, QiNiuUploadListener listener)`

上传 Bitmap 图片，token 自动获取。内部以 JPEG 格式压缩（quality=100）。

| 参数 | 类型 | 说明 |
|------|------|------|
| bitmap | Bitmap | 待上传图片，不能为 null 且不能已被 recycle |
| key | String | 七牛云上传路径 |
| listener | QiNiuUploadListener | 上传回调监听 |

#### `uploadImage(String token, Bitmap bitmap, String key, QiNiuUploadListener listener)`

上传 Bitmap 图片，使用自定义 token，JPEG quality=100。

#### `uploadImage(Bitmap bitmap, String key, int quality, QiNiuUploadListener listener)`

上传 Bitmap 图片，自定义 JPEG 压缩质量。

| 参数 | 类型 | 说明 |
|------|------|------|
| quality | int | JPEG 压缩质量，范围 1-100，值越大质量越高、文件越大 |

#### `uploadImage(String token, Bitmap bitmap, String key, int quality, QiNiuUploadListener listener)`

上传 Bitmap 图片，自定义 token + 自定义 JPEG 压缩质量。

---

### 字节数组上传

#### `uploadData(byte[] data, String key, QiNiuUploadListener listener)`

上传 byte[] 数据，token 自动获取。适用于已在内存中的数据。

| 参数 | 类型 | 说明 |
|------|------|------|
| data | byte[] | 待上传的字节数据 |
| key | String | 七牛云上传路径 |
| listener | QiNiuUploadListener | 上传回调监听 |

#### `uploadData(String token, byte[] data, String key, QiNiuUploadListener listener)`

上传 byte[] 数据，使用自定义 token。

---

## 使用示例

### 基本初始化

```java
// 在 Application 或首次使用时初始化（默认华东区域）
QiNiuFactory qiNiu = QiNiuFactory.getInstance(
    "your_access_key",
    "your_secret_key",
    "your_bucket_name",
    "https://cdn.example.com"
);

// 指定华南区域
QiNiuFactory qiNiu = QiNiuFactory.getInstance(
    "your_access_key",
    "your_secret_key",
    "your_bucket_name",
    "https://cdn.example.com",
    CustomZone.ZONE_HUA_NAN
);
```

### 上传本地文件

```java
String filePath = "/sdcard/Download/document.pdf";
String key = "files/" + System.currentTimeMillis() + ".pdf";

qiNiu.uploadFile(filePath, key, new QiNiuFactory.QiNiuUploadListener() {
    @Override
    public void uploadPercent(float percent) {
        // 主线程回调，可直接更新 UI
        progressBar.setProgress((int) (percent * 100));
    }

    @Override
    public void uploadSuccess(String url) {
        // url = "https://cdn.example.com/files/1712600000000.pdf"
        Toast.makeText(context, "上传成功: " + url, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void uploadFail(String url, String error) {
        Toast.makeText(context, "上传失败: " + error, Toast.LENGTH_SHORT).show();
    }
});
```

### 上传 Bitmap 图片

```java
Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.photo);
String key = "img/" + System.currentTimeMillis() + ".jpg";

qiNiu.uploadImage(bitmap, key, new QiNiuFactory.QiNiuUploadListener() {
    @Override
    public void uploadPercent(float percent) {
        Log.d("Upload", "进度: " + (int) (percent * 100) + "%");
    }

    @Override
    public void uploadSuccess(String url) {
        imageView.setTag(url); // 保存图片地址
    }

    @Override
    public void uploadFail(String url, String error) {
        Log.e("Upload", "图片上传失败: " + error);
    }
});
```

### 上传字节数组

```java
byte[] data = getByteArrayFromSomewhere();
String key = "data/" + System.currentTimeMillis() + ".bin";

qiNiu.uploadData(data, key, new QiNiuFactory.QiNiuUploadListener() {
    @Override
    public void uploadPercent(float percent) {
        // 进度回调
    }

    @Override
    public void uploadSuccess(String url) {
        Log.d("Upload", "数据上传成功: " + url);
    }

    @Override
    public void uploadFail(String url, String error) {
        Log.e("Upload", "数据上传失败: " + error);
    }
});
```

### 使用自定义 token 上传

```java
// 当 token 由服务端生成时，传入自定义 token
String serverToken = fetchTokenFromServer();

qiNiu.uploadFile(serverToken, filePath, key, new QiNiuFactory.QiNiuUploadListener() {
    @Override
    public void uploadPercent(float percent) { }

    @Override
    public void uploadSuccess(String url) {
        Log.d("Upload", "成功: " + url);
    }

    @Override
    public void uploadFail(String url, String error) {
        Log.e("Upload", "失败: " + error);
    }
});
```

### 自定义图片压缩质量

```java
// 压缩质量 80（减小文件体积）
qiNiu.uploadImage(bitmap, key, 80, new QiNiuFactory.QiNiuUploadListener() {
    @Override
    public void uploadPercent(float percent) { }

    @Override
    public void uploadSuccess(String url) {
        Log.d("Upload", "成功: " + url);
    }

    @Override
    public void uploadFail(String url, String error) {
        Log.e("Upload", "失败: " + error);
    }
});
```

### 配置重试策略

```java
// 自定义重试：最多5次，首次延迟3秒
qiNiu.setRetryConfig(5, 3000);

// 禁用重试
qiNiu.setRetryConfig(0, 0);
```

### 监听重试事件

```java
qiNiu.uploadFile(filePath, key, new QiNiuFactory.QiNiuUploadListener() {
    @Override
    public void uploadPercent(float percent) {
        progressBar.setProgress((int) (percent * 100));
    }

    @Override
    public void uploadSuccess(String url) {
        Toast.makeText(context, "上传成功", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void uploadFail(String url, String error) {
        // 重试全部耗尽后才会回调此方法
        Toast.makeText(context, "上传失败: " + error, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void uploadRetry(int currentAttempt, int maxAttempt, String error) {
        // 可选实现，用于显示重试状态
        Log.w("Upload", "第" + currentAttempt + "/" + maxAttempt + "次重试: " + error);
    }
});
```

### 释放资源

```java
// 在 Application.onTerminate() 或不再需要时调用
QiNiuFactory.destroy();
```

---

## 内部类说明（开发者参考）

以下类为库内部实现，使用者无需直接调用。

| 类名 | 职责 |
|------|------|
| `QiNiuManager` | 管理七牛 UploadManager 单例，根据区域构建上传配置（HTTPS、并发上传、超时等） |
| `QiNiuUtil` | 生成七牛云上传凭证（token），基于 HmacSHA1 签名 |
| `FileUtils` | 文件读取工具，支持文件大小限制（500MB） |
| `StringUtils` | 字符串工具及 Bitmap 转字节数组工具 |

---

## 注意事项

1. **密钥安全**：`AccessKey` 和 `SecretKey` 为敏感信息，生产环境建议由服务端生成 token，客户端使用 `uploadFile(token, ...)` 方式上传，避免在客户端暴露密钥。
2. **单例模式**：`QiNiuFactory` 为全局单例，首次调用 `getInstance()` 的参数生效，后续调用返回已有实例。如需更换配置，请先调用 `destroy()` 再重新初始化。
3. **主线程回调**：所有 `QiNiuUploadListener` 回调均自动切回主线程，可直接操作 UI 组件。
4. **大文件上传**：`uploadFile` 方法直接将 File 传给 SDK，支持分片上传，不会将整个文件读入内存。
5. **token 缓存**：内部自动缓存 token（有效期 3600 秒），并在到期前 300 秒自动刷新，无需手动管理。
6. **重试机制**：默认 3 次重试，指数退避延迟（第1次 2s、第2次 4s、第3次 8s）。仅对网络/服务端错误重试，参数错误不重试。
7. **destroy 安全**：调用 `destroy()` 后，进行中的上传会被取消，待重试任务不会再执行。
