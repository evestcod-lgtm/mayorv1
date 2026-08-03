package com.system.inputservice;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * DodgeEngine — ядро уклонения.
 *
 * Как работает:
 *   1. Каждые 80ms получаем bitmap с экрана (через MediaProjection)
 *   2. Сканируем «зону опасности» вокруг персонажа (центр экрана ±20%)
 *   3. Ищем жёлтые/белые пиксели снарядов BS
 *   4. Если снаряд найден — вычисляем направление угрозы
 *   5. Возвращаем DodgeResult с направлением уклона
 *
 * Два режима:
 *   MODE_NORMAL — страф перпендикулярно снаряду (чистый dodge)
 *   MODE_MORTIS — иногда делаем dash-тычку в сторону (имитация живого Mortis),
 *                 иногда пропускаем (не каждый снаряд — реалистично)
 *
 * Цвета снарядов в BS (2412×1080):
 *   Большинство снарядов: жёлто-оранжевые (#FFCC00–#FF8800)
 *   Некоторые: белые (#FFFFFF) или ярко-голубые (#00CCFF)
 *   Зелёные (#00FF44): HP бары союзников — игнорируем
 *
 * Зоны сканирования:
 *   DANGER_ZONE: 30%–70% по X, 25%–75% по Y (вокруг персонажа)
 *   SELF_ZONE:   45%–55% по X, 45%–55% по Y (позиция игрока — для определения стороны угрозы)
 */
public class DodgeEngine {

    public static final int MODE_NORMAL = 0;
    public static final int MODE_MORTIS = 1;

    // Цвет снарядов — жёлто-оранжевый спектр
    private static final int PROJ_R_MIN = 220;
    private static final int PROJ_G_MIN = 140;
    private static final int PROJ_B_MAX = 80;

    // Белые снаряды
    private static final int WHITE_MIN = 230;

    // Зона опасности (нормализованная)
    private static final float DZ_L = 0.28f, DZ_R = 0.72f;
    private static final float DZ_T = 0.22f, DZ_B = 0.78f;

    // Шаг сканирования — 5px баланс скорость/точность
    private static final int STEP = 5;

    // Минимум пикселей снаряда для срабатывания
    private static final int MIN_PROJ_PIXELS = 6;

    // Mortis: вероятность уклонения (не каждый снаряд)
    private static final float MORTIS_DODGE_CHANCE = 0.65f;

    private final int W, H;
    private final int mode;
    private final java.util.Random rng = new java.util.Random();

    // Cooldown между dodge'ами — не спамим
    private long lastDodgeMs = 0;
    private static final long DODGE_COOLDOWN_MS = 320L;

    // Счётчик уклонений
    private int dodgeCount = 0;

    public DodgeEngine(int screenW, int screenH, int mode) {
        this.W    = screenW;
        this.H    = screenH;
        this.mode = mode;
    }

    // ── Результат анализа кадра ───────────────────────────────────────────────

    public static class DodgeResult {
        public final boolean shouldDodge;
        public final double  dodgeAngleDeg;  // 0=right, 90=down, 180=left, 270=up
        public final boolean isDash;          // Mortis: dash-тычка (true) или страф (false)
        public final float   threatX;         // нормализованная X угрозы
        public final float   threatY;         // нормализованная Y угрозы

        DodgeResult(boolean dodge, double angle, boolean dash, float tx, float ty) {
            shouldDodge   = dodge;
            dodgeAngleDeg = angle;
            isDash        = dash;
            threatX       = tx;
            threatY       = ty;
        }

        static DodgeResult none() { return new DodgeResult(false, 0, false, 0, 0); }
    }

    // ─────────────────────────────────────────────────────────────────────────

    public DodgeResult analyze(Bitmap frame) {
        if (frame == null || frame.isRecycled()) return DodgeResult.none();

        long now = System.currentTimeMillis();
        // Cooldown с ±50ms рандомом — ещё один слой против паттерн-детекта
        long cooldown = DODGE_COOLDOWN_MS + (long)(rng.nextInt(101) - 50);
        if (now - lastDodgeMs < cooldown) return DodgeResult.none();

        // Границы зоны сканирования в пикселях
        int x0 = (int)(DZ_L * W), x1 = (int)(DZ_R * W);
        int y0 = (int)(DZ_T * H), y1 = (int)(DZ_B * H);

        // Центр персонажа
        float selfX = W * 0.50f;
        float selfY = H * 0.50f;

        // Массив пикселей зоны (один вызов — быстро)
        int zw = x1 - x0, zh = y1 - y0;
        int[] pixels = new int[zw * zh];
        frame.getPixels(pixels, 0, zw, x0, y0, zw, zh);

        // Накапливаем позиции снарядов
        float sumX = 0, sumY = 0;
        int   count = 0;

        for (int y = 0; y < zh; y += STEP) {
            int row = y * zw;
            for (int x = 0; x < zw; x += STEP) {
                int px = pixels[row + x];
                if (isBullet(px)) {
                    sumX += (x0 + x);
                    sumY += (y0 + y);
                    count++;
                }
            }
        }

        if (count < MIN_PROJ_PIXELS) return DodgeResult.none();

        // Центр масс угрозы
        float threatPxX = sumX / count;
        float threatPxY = sumY / count;
        float threatNX  = threatPxX / W;
        float threatNY  = threatPxY / H;

        // Вектор угрозы: от снаряда к персонажу
        double toSelfX = selfX - threatPxX;
        double toSelfY = selfY - threatPxY;

        // Перпендикуляр — два варианта, берём тот что дальше от края экрана
        double perpA = Math.toDegrees(Math.atan2(toSelfY, toSelfX)) + 90.0;
        double perpB = perpA + 180.0;
        perpA = ((perpA % 360) + 360) % 360;
        perpB = ((perpB % 360) + 360) % 360;

        double dodgeAngle = chooseSaferAngle(perpA, perpB);

        // Mortis mode: иногда пропускаем, иногда делаем dash
        if (mode == MODE_MORTIS) {
            if (rng.nextFloat() > MORTIS_DODGE_CHANCE) return DodgeResult.none();
            // Mortis dash — небольшое отклонение от перпендикуляра (±20°)
            dodgeAngle += (rng.nextDouble() * 40.0 - 20.0);
            dodgeAngle  = ((dodgeAngle % 360) + 360) % 360;
            lastDodgeMs = now;
            dodgeCount++;
            return new DodgeResult(true, dodgeAngle, true, threatNX, threatNY);
        }

        // Normal mode: чистый перпендикулярный страф
        lastDodgeMs = now;
        dodgeCount++;
        return new DodgeResult(true, dodgeAngle, false, threatNX, threatNY);
    }

    public int getDodgeCount() { return dodgeCount; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Снаряд = жёлто-оранжевый пиксель ИЛИ яркий белый.
     * Исключаем зелёный (HP бары) и синий (вода/фон карты).
     */
    private boolean isBullet(int px) {
        int r = Color.red(px), g = Color.green(px), b = Color.blue(px);

        // Жёлто-оранжевый: R высокий, G средний, B низкий
        if (r >= PROJ_R_MIN && g >= PROJ_G_MIN && b <= PROJ_B_MAX
                && r > g && g > b) return true;

        // Белый/светло-серый снаряд
        if (r >= WHITE_MIN && g >= WHITE_MIN && b >= WHITE_MIN) return true;

        return false;
    }

    /**
     * Из двух перпендикулярных направлений выбираем то,
     * которое ведёт дальше от краёв экрана (меньше риск упереться в стену).
     */
    private double chooseSaferAngle(double a, double b) {
        double rA = Math.toRadians(a), rB = Math.toRadians(b);
        float cx = W * 0.5f, cy = H * 0.5f;
        float stride = H * 0.15f;

        float axA = cx + (float)(Math.cos(rA) * stride);
        float ayA = cy + (float)(Math.sin(rA) * stride);
        float axB = cx + (float)(Math.cos(rB) * stride);
        float ayB = cy + (float)(Math.sin(rB) * stride);

        float distToEdgeA = Math.min(
            Math.min(axA, W - axA), Math.min(ayA, H - ayA));
        float distToEdgeB = Math.min(
            Math.min(axB, W - axB), Math.min(ayB, H - ayB));

        return distToEdgeA >= distToEdgeB ? a : b;
    }
}
