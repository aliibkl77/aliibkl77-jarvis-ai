package com.ali.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private EditText keyInput, repoInput, cmdInput;
    private TextView statusTxt;
    private Switch wakeSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#050811"));

        final SharedPreferences prefs = getSharedPreferences("jarvis_prefs", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#050811"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 60, 48, 60);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("J.A.R.V.I.S.  MARK VII");
        title.setTextColor(Color.parseColor("#00F3FF"));
        title.setTextSize(26);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusTxt = new TextView(this);
        statusTxt.setTextColor(Color.parseColor("#8FA8C8"));
        statusTxt.setTextSize(13);
        statusTxt.setPadding(0, 16, 0, 32);
        statusTxt.setGravity(Gravity.CENTER);
        root.addView(statusTxt);

        root.addView(makeBtn("1. Mikrofon, Kamera ve Arama Izinleri", "#10192C", "#00F3FF", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions(new String[]{
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.CAMERA,
                        Manifest.permission.CALL_PHONE
                }, 101);
            }
        }));

        root.addView(makeBtn("2. Uste Cizme (HUD Reaktoru) Izni", "#10192C", "#00F3FF", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } else {
                    Toast.makeText(MainActivity.this, "HUD izni aktif!", Toast.LENGTH_SHORT).show();
                }
            }
        }));

        root.addView(makeBtn("3. Sistem Ayarlari Yazma Izni (Parlaklik)", "#10192C", "#00F3FF", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Settings.System.canWrite(MainActivity.this)) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
                } else {
                    Toast.makeText(MainActivity.this, "Sistem yazma izni aktif!", Toast.LENGTH_SHORT).show();
                }
            }
        }));

        root.addView(makeBtn("4. Erisilebilirlik Motorunu Baslat", "#00F3FF", "#040814", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Indirilen Uygulamalar -> Jarvis AI -> Acik yap", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));

        LinearLayout wakeBox = new LinearLayout(this);
        wakeBox.setOrientation(LinearLayout.HORIZONTAL);
        wakeBox.setBackground(roundBg("#10192C", 24));
        wakeBox.setPadding(35, 28, 35, 28);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.setMargins(0, 28, 0, 0);
        wakeBox.setLayoutParams(wlp);

        TextView wakeLbl = new TextView(this);
        wakeLbl.setText("\"Hey Jarvis\" Surekli Dinleme");
        wakeLbl.setTextColor(Color.WHITE);
        wakeLbl.setTextSize(15);
        wakeLbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        wakeBox.addView(wakeLbl);

        wakeSwitch = new Switch(this);
        wakeSwitch.setChecked(prefs.getBoolean("wake_mode", false));
        wakeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                prefs.edit().putBoolean("wake_mode", isChecked).apply();
                if (JarvisService.instance != null) {
                    JarvisService.instance.setWakeWordMode(isChecked);
                }
            }
        });
        wakeBox.addView(wakeSwitch);
        root.addView(wakeBox);

        TextView lblKey = new TextView(this);
        lblKey.setText("Gemini API Anahtari:");
        lblKey.setTextColor(Color.parseColor("#5C7099"));
        lblKey.setPadding(0, 32, 0, 10);
        root.addView(lblKey);

        keyInput = new EditText(this);
        keyInput.setText(prefs.getString("api_key", ""));
        keyInput.setHint("AIzaSy...");
        keyInput.setHintTextColor(Color.parseColor("#5C7099"));
        keyInput.setTextColor(Color.WHITE);
        keyInput.setBackground(roundBg("#10192C", 24));
        keyInput.setPadding(35, 26, 35, 26);
        root.addView(keyInput);

        root.addView(makeBtn("API Anahtarini Kaydet", "#1B2A4A", "#FFFFFF", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putString("api_key", keyInput.getText().toString().trim()).apply();
                Toast.makeText(MainActivity.this, "Kaydedildi Ali!", Toast.LENGTH_SHORT).show();
            }
        }));

        TextView lblRepo = new TextView(this);
        lblRepo.setText("GitHub Repo (Bulut Beyin & OTA Guncelleme):");
        lblRepo.setTextColor(Color.parseColor("#5C7099"));
        lblRepo.setPadding(0, 32, 0, 10);
        root.addView(lblRepo);

        repoInput = new EditText(this);
        repoInput.setText(prefs.getString("gh_repo", ""));
        repoInput.setHint("kullaniciadi/jarvis-ai");
        repoInput.setHintTextColor(Color.parseColor("#5C7099"));
        repoInput.setTextColor(Color.WHITE);
        repoInput.setBackground(roundBg("#10192C", 24));
        repoInput.setPadding(35, 26, 35, 26);
        root.addView(repoInput);

        root.addView(makeBtn("Canli Beyni Guncelle (APK Kurmadan)", "#162440", "#00F3FF", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String repo = repoInput.getText().toString().trim();
                if (repo.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Once kullaniciadi/repo yaz Ali!", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit().putString("gh_repo", repo).apply();
                syncLiveBrain(repo);
            }
        }));

        root.addView(makeBtn("Buluttan Yeni APK Indir ve Kur (OTA)", "#162440", "#00E676", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String repo = repoInput.getText().toString().trim();
                if (repo.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Once kullaniciadi/repo yaz Ali!", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit().putString("gh_repo", repo).apply();
                if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                    Toast.makeText(MainActivity.this, "Bilinmeyen uygulama yukleme izni ver!", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                    return;
                }
                downloadAndInstallApk(repo);
            }
        }));

        TextView lblCmd = new TextView(this);
        lblCmd.setText("Hizli Komut / Sohbet Testi:");
        lblCmd.setTextColor(Color.parseColor("#5C7099"));
        lblCmd.setPadding(0, 32, 0, 10);
        root.addView(lblCmd);

        cmdInput = new EditText(this);
        cmdInput.setHint("Orn: Ekranda ne var? / Feneri ac");
        cmdInput.setHintTextColor(Color.parseColor("#5C7099"));
        cmdInput.setTextColor(Color.WHITE);
        cmdInput.setBackground(roundBg("#10192C", 24));
        cmdInput.setPadding(35, 26, 35, 26);
        root.addView(cmdInput);

        root.addView(makeBtn("KOMUTU GONDER", "#00E676", "#040814", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String g = cmdInput.getText().toString().trim();
                if (!g.isEmpty()) {
                    if (JarvisService.instance != null) {
                        JarvisService.instance.executeGoal(g);
                        cmdInput.setText("");
                    } else {
                        Toast.makeText(MainActivity.this, "Once 4. adimdan Erisilebilirlik servisini ac!", Toast.LENGTH_LONG).show();
                    }
                }
            }
        }));

        setContentView(scroll);
    }

    private void syncLiveBrain(final String repo) {
        Toast.makeText(this, "Buluttan yeni beyin kurallari cekiliyor...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String[] branches = {"main", "master"};
                for (String br : branches) {
                    try {
                        URL url = new URL("https://raw.githubusercontent.com/" + repo + "/" + br + "/brain.txt?t=" + System.currentTimeMillis());
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(6000);
                        if (conn.getResponseCode() == 200) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                            getSharedPreferences("jarvis_prefs", MODE_PRIVATE).edit().putString("live_brain", sb.toString()).apply();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "Jarvis beyni guncellendi Ali!", Toast.LENGTH_LONG).show();
                                }
                            });
                            return;
                        }
                    } catch (Exception ignored) {}
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "brain.txt bulunamadi. Repo adini kontrol et.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void downloadAndInstallApk(final String repo) {
        Toast.makeText(this, "Son APK indiriliyor, bekle Ali...", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL("https://github.com/" + repo + "/releases/download/latest/Jarvis.apk");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);

                    if (conn.getResponseCode() != 200) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "Release bulunamadi! GitHub Actions derlemesinin bitmesini bekle.", Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }

                    File outFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "JarvisUpdate.apk");
                    InputStream is = conn.getInputStream();
                    FileOutputStream fos = new FileOutputStream(outFile);
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        fos.write(buf, 0, len);
                    }
                    fos.close();
                    is.close();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Uri apkUri = Uri.parse("content://com.ali.jarvis.apkprovider/JarvisUpdate.apk");
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "Indirme hatasi: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean ov = Settings.canDrawOverlays(this);
        boolean acc = JarvisService.instance != null;
        statusTxt.setText("HUD Reaktoru: " + (ov ? "AKTIF" : "KAPALI") + "  |  Sistem Motoru: " + (acc ? "AKTIF" : "KAPALI"));
    }

    private Button makeBtn(String txt, String bg, String fg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(txt);
        b.setTextColor(Color.parseColor(fg));
        b.setBackground(roundBg(bg, 26));
        b.setAllCaps(false);
        b.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 16, 0, 0);
        b.setLayoutParams(lp);
        b.setPadding(28, 28, 28, 28);
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable roundBg(String hex, int r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(hex));
        d.setCornerRadius(r);
        return d;
    }
}
