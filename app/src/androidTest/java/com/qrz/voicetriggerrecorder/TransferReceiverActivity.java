package com.qrz.voicetriggerrecorder;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Separate APK/UID: uses only platform classes, since the test APK excludes app runtime libraries. */
public class TransferReceiverActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String result;
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null || !"content".equals(uri.getScheme())) throw new IllegalStateException("Invalid URI");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Missing stream");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())) {
                hash.append(String.format(java.util.Locale.ROOT, "%02x", value));
            }
            boolean writeDenied = false;
            try (OutputStream ignored = getContentResolver().openOutputStream(uri, "w")) {
                // A successful open is already a failed permission test.
            } catch (SecurityException expected) { writeDenied = true; }
            boolean deleteDenied = false;
            try { getContentResolver().delete(uri, null, null); }
            catch (SecurityException expected) { deleteDenied = true; }
            if (!writeDenied || !deleteDenied) throw new IllegalStateException("Write/delete access granted");
            result = "SHARE_OK:" + hash;
        } catch (Exception error) {
            result = "SHARE_FAILED:" + error.getClass().getSimpleName() + ":" + error.getMessage();
        }
        TextView text = new TextView(this);
        text.setText(result);
        text.setTextSize(18f);
        setContentView(text);
    }
}
