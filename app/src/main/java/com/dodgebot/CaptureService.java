package com.system.inputservice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * CaptureService — foreground service.
 * Захватывает экран через MediaProjection и запускает DodgeEngine каждые 80ms.
 * Результат отправляет в DodgeService через broadcast.
 *
 * Нотификация содержит кнопки ON/OFF и переключатель режима.
 */
public class CaptureService extends Service {

    private static final String TAG        = "CaptureService";
    private static final String CHANNEL_ID = "dodge_channel";
    private static final int    NOTIF_ID   = 7;

    public static final String ACTION_START        = "com.system.inputservice.START";
    public static final String ACTION_STOP         = "com.system.inputservice.STOP";
    public static final String ACTION_TOGGLE_MODE  = "com.system.inputservice.TOGGLE_MODE";
    public static final String EXTRA_RESULT_DATA   = "result_data";
    public static final String EXTRA_MODE          = "mode";

    // Интервал анализа кадров (ms)
    private static final long TICK_MS = 80L;

    public static boolean isRunning = false;
    public static int     currentMode = DodgeEngine.MODE_NORMAL;

    // ── Capture pipeline ──────────────────────────────────────────────────────
    private MediaProjection  mediaProjection;
    private VirtualDisplay   virtualDisplay;
    private ImageReader      imageReader;
    private HandlerThread    captureThread;
    private Handler          captureHandler;
    private HandlerThread    analysisThread;
    private Handler          analysisHandler;

    private DodgeEngine      engine;
    private int screenW = 2412, screenH = 1080, screenDpi = 480;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_TOGGLE_MODE.equals(action)) {
            currentMode = (currentMode == DodgeEngine.MODE_NORMAL)
                ? DodgeEngine.MODE_MORTIS : DodgeEngine.MODE_NORMAL;
            if (engine != null) {
                engine = new DodgeEngine(screenW, screenH, currentMode);
            }
            updateNotification();
            broadcastStatus();
            return START_STICKY;
        }

        if (ACTION_START.equals(action)) {
            Intent projData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            currentMode     = intent.getIntExtra(EXTRA_MODE, DodgeEngine.MODE_NORMAL);
            startCapture(projData);
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }

    // ── Start / Stop ──────────────────────────────────────────────────────────

    private void startCapture(Intent projData) {
        createChannel();

        // Start foreground immediately
        startForeground(NOTIF_ID, buildNotification());

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW   = dm.widthPixels  > 0 ? dm.widthPixels  : 2412;
        screenH   = dm.heightPixels > 0 ? dm.heightPixels : 1080;
        screenDpi = dm.densityDpi   > 0 ? dm.densityDpi   : 480;

        engine = new DodgeEngine(screenW, screenH, currentMode);

        MediaProjectionManager mgr =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mgr.getMediaProjection(android.app.Activity.RESULT_OK, projData);
        if (mediaProjection == null) { stopSelf(); return; }

        captureThread = new HandlerThread("Dodge-Capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        analysisThread = new HandlerThread("Dodge-Analysis");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "DodgeCapture", screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(), null, captureHandler);

        isRunning = true;
        analysisHandler.post(analysisLoop);
        broadcastStatus();
        Log.i(TAG, "Capture started. Mode=" + currentMode);
    }

    private void stopCapture() {
        isRunning = false;
        if (analysisHandler != null) analysisHandler.removeCallbacks(analysisLoop);
        if (virtualDisplay   != null) { virtualDisplay.release();   virtualDisplay   = null; }
        if (imageReader      != null) { imageReader.close();        imageReader      = null; }
        if (mediaProjection  != null) { mediaProjection.stop();     mediaProjection  = null; }
        if (captureThread    != null) { captureThread.quitSafely(); captureThread    = null; }
        if (analysisThread   != null) { analysisThread.quitSafely();analysisThread   = null; }
        engine = null;
        broadcastStatus();
        Log.i(TAG, "Capture stopped");
    }

    // ── Analysis loop ─────────────────────────────────────────────────────────

    private final Runnable analysisLoop = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            Bitmap frame = captureFrame();
            if (frame != null && engine != null) {
                DodgeEngine.DodgeResult result = engine.analyze(frame);
                frame.recycle();

                if (result.shouldDodge) {
                    // Отправляем команду в DodgeService
                    Intent i = new Intent(DodgeService.ACTION_DODGE);
                    i.putExtra(DodgeService.EXTRA_ANGLE, result.dodgeAngleDeg);
                    i.putExtra(DodgeService.EXTRA_DASH,  result.isDash);
                    i.setPackage(getPackageName());
                    sendBroadcast(i);

                    // Обновляем счётчик в нотификации каждые 5 уклонений
                    if (engine.getDodgeCount() % 5 == 0) updateNotification();
                }
            } else if (frame != null) {
                frame.recycle();
            }

            if (isRunning) analysisHandler.postDelayed(this, TICK_MS);
        }
    };

    // ── Screen capture ────────────────────────────────────────────────────────

    private Bitmap captureFrame() {
        if (imageReader == null) return null;
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return null;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer  buf   = plane.getBuffer();
            int paddedW       = plane.getRowStride() / plane.getPixelStride();

            Bitmap raw = Bitmap.createBitmap(paddedW, screenH, Bitmap.Config.ARGB_8888);
            raw.copyPixelsFromBuffer(buf);

            if (paddedW != screenW) {
                Bitmap c = Bitmap.createBitmap(raw, 0, 0, screenW, screenH);
                raw.recycle();
                return c;
            }
            return raw;
        } catch (Exception e) {
            return null;
        } finally {
            if (image != null) image.close();
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "DodgeBot", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        // Tap → открыть MainActivity
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Кнопка STOP
        PendingIntent stopPi = PendingIntent.getService(this, 1,
            new Intent(this, CaptureService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Кнопка переключения режима
        String modeLabel = currentMode == DodgeEngine.MODE_MORTIS ? "→ Обычный" : "→ Mortis";
        PendingIntent modePi = PendingIntent.getService(this, 2,
            new Intent(this, CaptureService.class).setAction(ACTION_TOGGLE_MODE),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String modeName = currentMode == DodgeEngine.MODE_MORTIS ? "Mortis 🦇" : "Обычный";
        String dodges   = engine != null ? "Уклонений: " + engine.getDodgeCount() : "";

        return new android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ DodgeBot АКТИВЕН")
            .setContentText("Режим: " + modeName + "  " + dodges)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "СТОП", stopPi)
            .addAction(android.R.drawable.ic_menu_rotate, modeLabel, modePi)
            .build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    // ── Status broadcast ──────────────────────────────────────────────────────

    public static final String ACTION_STATUS = "com.system.inputservice.STATUS";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_COUNT   = "count";
    public static final String EXTRA_CURMODE = "mode";

    private void broadcastStatus() {
        Intent i = new Intent(ACTION_STATUS);
        i.putExtra(EXTRA_RUNNING, isRunning);
        i.putExtra(EXTRA_COUNT,   engine != null ? engine.getDodgeCount() : 0);
        i.putExtra(EXTRA_CURMODE, currentMode);
        sendBroadcast(i);
    }
}
