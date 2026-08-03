package com.dodgebot;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

public class ProjectionActivity extends Activity {
    private static final int REQ = 1001;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        MediaProjectionManager m =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ && res == RESULT_OK && data != null) {
            Intent i = new Intent(this, CaptureService.class);
            i.setAction(CaptureService.ACTION_START);
            i.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
            i.putExtra(CaptureService.EXTRA_MODE,
                getIntent().getIntExtra(CaptureService.EXTRA_MODE, DodgeEngine.MODE_NORMAL));
            startForegroundService(i);
        }
        finish();
    }
}
