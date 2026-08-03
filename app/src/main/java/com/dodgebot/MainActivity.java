package com.dodgebot;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.dodgebot.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private final Handler mainH = new Handler(Looper.getMainLooper());
    private static final int REQ_NOTIF = 100;

    private boolean dodgeActive = false;

    private final BroadcastReceiver statusRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            boolean running = i.getBooleanExtra(CaptureService.EXTRA_RUNNING, false);
            int     count   = i.getIntExtra(CaptureService.EXTRA_COUNT, 0);
            int     mode    = i.getIntExtra(CaptureService.EXTRA_CURMODE, DodgeEngine.MODE_NORMAL);
            updateUI(running, count, mode);
        }
    };

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            refreshAccessStatus();
            mainH.postDelayed(this, 2000);
        }
    };

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        requestNotifPermission();

        binding.btnToggle.setOnClickListener(v -> toggleDodge());
        binding.btnAccessibility.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        binding.rgMode.setOnCheckedChangeListener((g, id) -> {
            boolean mortis = (id == R.id.rbMortis);
            binding.tvModeDesc.setText(mortis
                ? "Mortis: dash-тычка в сторону от снаряда (срабатывает ~65% времени — реалистично)"
                : "Уклон: страф в сторону от снаряда");
            // Если уже запущен — переключаем режим на лету
            if (CaptureService.isRunning) {
                Intent i = new Intent(this, CaptureService.class);
                i.setAction(CaptureService.ACTION_TOGGLE_MODE);
                startService(i);
            }
        });

        IntentFilter f = new IntentFilter(CaptureService.ACTION_STATUS);
        registerReceiver(statusRx, f, Context.RECEIVER_NOT_EXPORTED);
        mainH.post(poller);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainH.removeCallbacks(poller);
        try { unregisterReceiver(statusRx); } catch (Exception ignored) {}
    }

    // ── Toggle ────────────────────────────────────────────────────────────────

    private void toggleDodge() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Сначала включи Accessibility service!", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        if (CaptureService.isRunning) {
            // Останавливаем
            Intent i = new Intent(this, CaptureService.class);
            i.setAction(CaptureService.ACTION_STOP);
            startService(i);
        } else {
            // Запускаем через ProjectionActivity
            int mode = (binding.rgMode.getCheckedRadioButtonId() == R.id.rbMortis)
                ? DodgeEngine.MODE_MORTIS : DodgeEngine.MODE_NORMAL;
            Intent i = new Intent(this, ProjectionActivity.class);
            i.putExtra(CaptureService.EXTRA_MODE, mode);
            startActivity(i);
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void updateUI(boolean running, int count, int mode) {
        dodgeActive = running;

        binding.tvDodgeStatus.setText(running ? "● ВКЛЮЧЁН" : "● ВЫКЛЮЧЕН");
        binding.tvDodgeStatus.setTextColor(running ? 0xFF00FF88 : 0xFFFF4444);

        binding.btnToggle.setText(running ? "ВЫКЛЮЧИТЬ DODGE" : "ВКЛЮЧИТЬ DODGE");
        int btnColor = running ? 0xFFFF4444 : 0xFF00FF88;
        binding.btnToggle.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(btnColor));
        binding.btnToggle.setTextColor(running ? 0xFFFFFFFF : 0xFF000000);

        binding.tvDodgeCount.setText("Уклонений: " + count);
        binding.tvMode.setText("Режим: " + (mode == DodgeEngine.MODE_MORTIS ? "Mortis 🦇" : "Обычный"));
    }

    private void refreshAccessStatus() {
        boolean ok = isAccessibilityEnabled();
        binding.tvAccessStatus.setText("Accessibility: " + (ok ? "✓ включён" : "✗ не включён"));
        binding.tvAccessStatus.setTextColor(ok ? 0xFF00C853 : 0xFFFF4444);
    }

    private boolean isAccessibilityEnabled() {
        if (DodgeService.instance != null) return true;
        try {
            String s = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getPackageName());
        } catch (Exception e) { return false; }
    }

    // ── Notification permission ───────────────────────────────────────────────

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }
}
