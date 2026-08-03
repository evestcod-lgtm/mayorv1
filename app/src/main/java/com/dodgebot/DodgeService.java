package com.dodgebot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * DodgeService — AccessibilityService.
 * Единственная роль: принимать DodgeResult от CaptureService
 * и выполнять GestureDescription (страф или dash).
 *
 * Почему это не триггерит Shield:
 *   - canRetrieveWindowContent=false
 *   - packageNames не указан (не таргетируем BS)
 *   - flagDefault без FLAG_RETRIEVE_INTERACTIVE_WINDOWS
 *   - Нет оверлей-окна поверх игры
 */
public class DodgeService extends AccessibilityService {

    private static final String TAG = "DodgeService";

    public static final String ACTION_DODGE = "com.dodgebot.DODGE";
    public static final String EXTRA_ANGLE  = "angle";
    public static final String EXTRA_DASH   = "dash";

    // Singleton — CaptureService использует для dispatch
    public static DodgeService instance;

    // Размеры экрана (устанавливаются при connect)
    private int screenW = 2412;
    private int screenH = 1080;

    // Параметры жестов
    private static final long STRAFE_MS = 140L;  // длительность страфа
    private static final long DASH_MS   = 70L;   // длительность dash-тычки Mortis
    private static final float STRAFE_R = 0.18f; // радиус страфа (от screenH)
    private static final float DASH_R   = 0.22f; // радиус dash (чуть дальше)

    // Joystick зоны (калиброваны под 2412×1080)
    // Движение: левый джойстик центр
    private static final float JOY_X = 0.155f;
    private static final float JOY_Y = 0.700f;
    // Атака (для Mortis dash — правый джойстик)
    private static final float ATK_X = 0.820f;
    private static final float ATK_Y = 0.700f;

    private final BroadcastReceiver dodgeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_DODGE.equals(intent.getAction())) return;
            double angle = intent.getDoubleExtra(EXTRA_ANGLE, 0.0);
            boolean dash = intent.getBooleanExtra(EXTRA_DASH, false);
            performDodge(angle, dash);
        }
    };

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels  > 0 ? dm.widthPixels  : 2412;
        screenH = dm.heightPixels > 0 ? dm.heightPixels : 1080;

        IntentFilter f = new IntentFilter(ACTION_DODGE);
        registerReceiver(dodgeReceiver, f, Context.RECEIVER_NOT_EXPORTED);

        Log.i(TAG, "DodgeService connected. Screen: " + screenW + "×" + screenH);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        try { unregisterReceiver(dodgeReceiver); } catch (Exception ignored) {}
        return super.onUnbind(intent);
    }

    // ── Gesture dispatch ──────────────────────────────────────────────────────

    /**
     * Выполняет уклонение:
     *   dash=false → страф левым джойстиком (обычный режим)
     *   dash=true  → тычка правым джойстиком (Mortis атака в сторону)
     */
    private void performDodge(double angleDeg, boolean dash) {
        double rad     = Math.toRadians(angleDeg);
        float  radius  = H() * (dash ? DASH_R : STRAFE_R);
        float  dx      = (float)(Math.cos(rad) * radius);
        float  dy      = (float)(Math.sin(rad) * radius);
        long   duration = dash ? DASH_MS : STRAFE_MS;

        // Для страфа — левый джойстик
        // Для Mortis dash — одновременно движение + атака в том же направлении
        float cx, cy;
        if (dash) {
            // Атака (dash через врага / в сторону от снаряда)
            cx = screenW * ATK_X;
            cy = screenH * ATK_Y;
        } else {
            cx = screenW * JOY_X;
            cy = screenH * JOY_Y;
        }

        Path path = new Path();
        path.moveTo(cx, cy);
        path.lineTo(cx + dx, cy + dy);

        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0L, duration))
            .build();

        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                // Если это был dash Mortis — возвращаем джойстик в нейтраль
                if (dash) returnToNeutral();
            }
            @Override
            public void onCancelled(GestureDescription g) {}
        }, null);

        Log.d(TAG, "Dodge: angle=" + (int)angleDeg + " dash=" + dash);
    }

    /**
     * После Mortis dash возвращаем правый джойстик в центр
     * чтобы не застрять в атаке.
     */
    private void returnToNeutral() {
        float cx = screenW * ATK_X;
        float cy = screenH * ATK_Y;

        Path p = new Path();
        p.moveTo(cx, cy);

        GestureDescription g = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p, 0L, 50L))
            .build();
        dispatchGesture(g, null, null);
    }

    private int H() { return screenH; }

    public boolean isActive() { return instance != null; }
}
