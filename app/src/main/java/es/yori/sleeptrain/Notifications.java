package es.yori.sleeptrain;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.net.Uri;

final class Notifications {
    static Notification build(Context context, boolean alarm, String title, String text, String token) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String channelId = alarm ? "alarm-v1" : "journey-v1";
        NotificationChannel channel = new NotificationChannel(channelId, alarm ? "Avisos de llegada" : "Viaje en curso",
                alarm ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_LOW);
        // Audio and vibration are handled by the alarm player to honor the selected output.
        channel.setSound(null, null); channel.enableVibration(false);
        manager.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getBroadcast(context, 51,
                new Intent(context, BackupAlarmReceiver.class).setAction(alarm ? "ACKNOWLEDGE" : "STOP")
                        .setData(new Uri.Builder().scheme("sleeptrain").authority("journey").appendPath(token).build())
                        .putExtra("token", token),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, channelId).setSmallIcon(R.drawable.ic_train)
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(alarm ? Notification.CATEGORY_ALARM : Build.VERSION.SDK_INT >= 28
                        ? Notification.CATEGORY_NAVIGATION : Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, alarm ? "Estoy despierto" : "Finalizar viaje", stop).build())
                .build();
    }
}

