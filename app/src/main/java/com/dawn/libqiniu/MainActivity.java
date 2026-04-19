package com.dawn.libqiniu;

import androidx.appcompat.app.AppCompatActivity;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.dawn.qiniu.QiNiuFactory;

import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // 替换为实际的七牛云配置
    private static final String ACCESS = "";
    private static final String SECRET = "";
    private static final String BUCKET = "";
    private static final String PHOTO_HOST = "";

    private QiNiuFactory qiNiuFactory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        qiNiuFactory = QiNiuFactory.getInstance(ACCESS, SECRET, BUCKET, PHOTO_HOST);

        Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.demo_1);
        TextView tvUpload = findViewById(R.id.tv_upload);
        tvUpload.setOnClickListener(view -> uploadImage(bitmap));
    }

    private void uploadImage(Bitmap bitmap) {
        String key = "img/" + System.currentTimeMillis() + new Random().nextInt(1000) + ".jpg";
        Log.d(TAG, "上传 key: " + key);
        qiNiuFactory.uploadImage(bitmap, key, new QiNiuFactory.QiNiuUploadListener() {
            @Override
            public void uploadPercent(float percent) {
                Log.d(TAG, "上传进度: " + (int) (percent * 100) + "%");
            }

            @Override
            public void uploadSuccess(String url) {
                Log.d(TAG, "上传成功: " + url);
            }

            @Override
            public void uploadFail(String url, String error) {
                Log.e(TAG, "上传失败: " + url + " - " + error);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        QiNiuFactory.destroy();
    }
}