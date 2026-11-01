package com.tzk.xgbms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class Keep extends Service {

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel ch = new NotificationChannel("xgbms", "XGBMS", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification n;
            if (Build.VERSION.SDK_INT >= 26) {
                n = new Notification.Builder(this, "xgbms")
                        .setContentTitle("XGBMS")
                        .setContentText("正在连接电池…")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setOngoing(true)
                        .build();
            } else {
                n = new Notification.Builder(this)
                        .setContentTitle("XGBMS")
                        .setContentText("正在连接电池…")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setOngoing(true)
                        .build();
            }
            startForeground(1, n);
        } catch (Exception e) { }
        return START_STICKY;
    }
}
