package com.example.an_biliticketsbuy.ntp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;

import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NTP 时间同步
 *
 * 设备本地时间可能不准，通过 NTP 服务器获取精确时间，计算偏移量。
 */
public class NtpTimeSync {

    private static final String TAG = "NtpTimeSync";

    private static final String[] NTP_SERVERS = {
        "ntp.aliyun.com",
        "ntp.tencent.com",
        "cn.ntp.org.cn",
        "time.windows.com",
        "pool.ntp.org"
    };

    private static final int NTP_TIMEOUT = 5000;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface SyncCallback {
        void onSuccess(long offsetMillis);
        void onFailure(String error);
    }

    /**
     * 异步同步时间，回调在主线程执行
     */
    public void sync(SyncCallback callback) {
        executor.execute(() -> {
            NTPUDPClient client = new NTPUDPClient();
            client.setDefaultTimeout(NTP_TIMEOUT);

            for (String server : NTP_SERVERS) {
                try {
                    client.open();
                    Log.d(TAG, "Trying NTP server: " + server);
                    InetAddress address = InetAddress.getByName(server);
                    TimeInfo timeInfo = client.getTime(address);
                    timeInfo.computeDetails();

                    Long offset = timeInfo.getOffset();
                    if (offset != null) {
                        long offsetMillis = offset;
                        Log.i(TAG, "NTP sync success: " + server
                                + ", offset=" + offsetMillis + "ms");
                        TimeOffsetHolder.setOffset(offsetMillis);
                        mainHandler.post(() -> callback.onSuccess(offsetMillis));
                        client.close();
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "NTP server failed: " + server + " - " + e.getMessage());
                } finally {
                    try { client.close(); } catch (Exception ignored) {}
                }
            }

            mainHandler.post(() -> callback.onFailure("所有 NTP 服务器均不可达"));
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
