package com.jarvis.ali.ai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements JarvisService.UiCallback {

    private static final int REQ_PERMISSIONS = 101;

    private ArcReactorView reactorView;
    private TextView tvStatusMain;
    private TextView tvStatusSub;
    private TextView chipAccessibility;
    private TextView chipOverlay;
    private TextView chipMic;
    private TextView chipContacts;
    private LinearLayout chatContainer;
    private ScrollView chatScroll;
    private EditText etCommand;
    private TextView btnMic;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE);
        if (!prefs.contains("github_repo")) {
            prefs.edit().putString("github_repo", "aliibkl77/aliibkl77-jarvis-ai").apply();
        }

        buildHudLayout();
        checkInitialPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        JarvisService.setUiCallback(this);
        refreshStatusChips();
        JarvisService service = JarvisService.getInstance();
        if (service != null) {
            onStateChanged(service.getCurrentState(), service.getCurrentStatusText(), service.getCurrentSubText());
        } else {
            onStateChanged(ArcReactorView.STATE_ERROR, "OTONOM MOTOR KAPALI", "Yukarıdaki OTONOM butonuna basıp Erişilebilirlik iznini aç Ali");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        JarvisService.setUiCallback(null);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()
        );
    }

    private GradientDrawable makeCard(String fillHex, String strokeHex, int strokeDp, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(fillHex));
        if (strokeHex != null && strokeDp > 0) {
            gd.setStroke(dp(strokeDp), Color.parseColor(strokeHex));
        }
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private void buildHudLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#060913"));
        root.setPadding(dp(14), dp(12), dp(14), dp(12));

        // 1. TOP HEADER BAR
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(4), dp(2), dp(4), dp(10));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvBrand = new TextView(this);
        tvBrand.setText("J.A.R.V.I.S.");
        tvBrand.setTextColor(Color.parseColor("#F8FAFC"));
        tvBrand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tvBrand.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvBrand.setLetterSpacing(0.15f);

        TextView tvSubBrand = new TextView(this);
        tvSubBrand.setText("MARK VII // AUTONOMOUS OS");
        tvSubBrand.setTextColor(Color.parseColor("#00F0FF"));
        tvSubBrand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvSubBrand.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvSubBrand.setLetterSpacing(0.12f);

        titleBox.addView(tvBrand);
        titleBox.addView(tvSubBrand);
        topBar.addView(titleBox);

        TextView btnOta = createPillButton("GÜNCELLE", "#0D192E", "#00F0FF");
        btnOta.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JarvisService svc = JarvisService.getInstance();
                if (svc != null) {
                    svc.syncBrainAndCheckUpdate(true);
                } else {
                    Toast.makeText(MainActivity.this, "Önce OTONOM iznini aç kanka", Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams otaLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        otaLp.rightMargin = dp(8);
        topBar.addView(btnOta, otaLp);

        TextView btnSettings = createPillButton("AYARLAR", "#0D1326", "#64748B");
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });
        topBar.addView(btnSettings);

        root.addView(topBar);

        // 2. SYSTEM PERMISSION TELEMETRY STRIP
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams chipRowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipRowLp.bottomMargin = dp(10);
        chipRow.setLayoutParams(chipRowLp);

        chipAccessibility = createStatusChip("OTONOM");
        chipOverlay = createStatusChip("OVERLAY");
        chipMic = createStatusChip("MİKROFON");
        chipContacts = createStatusChip("REHBER");

        chipAccessibility.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "İndirilen Uygulamalar > J.A.R.V.I.S. aç", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        chipOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } else {
                    Toast.makeText(MainActivity.this, "Üst ekran izni aktif", Toast.LENGTH_SHORT).show();
                }
            }
        });

        View.OnClickListener permClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkInitialPermissions();
            }
        };
        chipMic.setOnClickListener(permClick);
        chipContacts.setOnClickListener(permClick);

        chipRow.addView(chipAccessibility);
        chipRow.addView(chipOverlay);
        chipRow.addView(chipMic);
        chipRow.addView(chipContacts);
        root.addView(chipRow);

        // 3. ARC REACTOR CORE CARD
        LinearLayout reactorCard = new LinearLayout(this);
        reactorCard.setOrientation(LinearLayout.VERTICAL);
        reactorCard.setGravity(Gravity.CENTER);
        reactorCard.setBackground(makeCard("#0A1020", "#1E293B", 1, 18));
        reactorCard.setPadding(dp(12), dp(12), dp(12), dp(14));
        LinearLayout.LayoutParams reactorCardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        reactorCardLp.bottomMargin = dp(10);
        reactorCard.setLayoutParams(reactorCardLp);

        reactorView = new ArcReactorView(this);
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(dp(165), dp(165));
        reactorView.setLayoutParams(rvLp);
        reactorView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JarvisService svc = JarvisService.getInstance();
                if (svc != null) {
                    svc.toggleVoiceListening();
                } else {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }
            }
        });
        reactorCard.addView(reactorView);

        tvStatusMain = new TextView(this);
        tvStatusMain.setText("SİSTEM HAZIR");
        tvStatusMain.setTextColor(Color.parseColor("#00F0FF"));
        tvStatusMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvStatusMain.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvStatusMain.setGravity(Gravity.CENTER);
        tvStatusMain.setPadding(0, dp(6), 0, dp(2));
        reactorCard.addView(tvStatusMain);

        tvStatusSub = new TextView(this);
        tvStatusSub.setText("Reaktöre dokun konuş veya aşağıdan görev ver Ali");
        tvStatusSub.setTextColor(Color.parseColor("#94A3B8"));
        tvStatusSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvStatusSub.setGravity(Gravity.CENTER);
        reactorCard.addView(tvStatusSub);

        root.addView(reactorCard);

        // 4. LIVE AUTONOMOUS TERMINAL / CHAT FEED
        chatScroll = new ScrollView(this);
        chatScroll.setBackground(makeCard("#080D1B", "#1E293B", 1, 16));
        chatScroll.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.bottomMargin = dp(10);
        chatScroll.setLayoutParams(scrollLp);

        chatContainer = new LinearLayout(this);
        chatContainer.setOrientation(LinearLayout.VERTICAL);
        chatScroll.addView(chatContainer);
        root.addView(chatScroll);

        appendLog("JARVIS", "Mark VII Otonom Motor devrede Ali. 'WhatsApp'a gir neneme merhaba de' gibi çok adımlı görevleri verebilirsin.");

        // 5. BOTTOM COMMAND DOCK
        LinearLayout bottomDock = new LinearLayout(this);
        bottomDock.setOrientation(LinearLayout.HORIZONTAL);
        bottomDock.setGravity(Gravity.CENTER_VERTICAL);
        bottomDock.setBackground(makeCard("#0D1428", "#00F0FF", 1, 24));
        bottomDock.setPadding(dp(12), dp(6), dp(6), dp(6));

        etCommand = new EditText(this);
        etCommand.setHint("Görev yaz (örn: WhatsApp'a gir neneme merhaba de)");
        etCommand.setHintTextColor(Color.parseColor("#64748B"));
        etCommand.setTextColor(Color.parseColor("#F8FAFC"));
        etCommand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        etCommand.setBackgroundColor(Color.TRANSPARENT);
        etCommand.setMaxLines(3);
        etCommand.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomDock.addView(etCommand);

        btnMic = new TextView(this);
        btnMic.setText("MIC");
        btnMic.setTextColor(Color.parseColor("#00FF9D"));
        btnMic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btnMic.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnMic.setGravity(Gravity.CENTER);
        btnMic.setBackground(makeCard("#0A241D", "#00FF9D", 1, 18));
        btnMic.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        micLp.rightMargin = dp(6);
        btnMic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JarvisService svc = JarvisService.getInstance();
                if (svc != null) {
                    svc.toggleVoiceListening();
                } else {
                    Toast.makeText(MainActivity.this, "Önce OTONOM iznini aç Ali", Toast.LENGTH_SHORT).show();
                }
            }
        });
        bottomDock.addView(btnMic, micLp);

        TextView btnSend = new TextView(this);
        btnSend.setText("GÖNDER");
        btnSend.setTextColor(Color.parseColor("#060913"));
        btnSend.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btnSend.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnSend.setGravity(Gravity.CENTER);
        btnSend.setBackground(makeCard("#00F0FF", null, 0, 18));
        btnSend.setPadding(dp(12), dp(9), dp(12), dp(9));
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String cmd = etCommand.getText().toString().trim();
                if (TextUtils.isEmpty(cmd)) return;
                etCommand.setText("");
                sendMissionCommand(cmd);
            }
        });
        bottomDock.addView(btnSend);

        root.addView(bottomDock);
        setContentView(root);
    }

    private TextView createPillButton(String text, String fillHex, String strokeHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(strokeHex));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setBackground(makeCard(fillHex, strokeHex, 1, 14));
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        return tv;
    }

    private TextView createStatusChip(String label) {
        TextView tv = new TextView(this);
        tv.setText("● " + label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(6), dp(5), dp(6), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void styleChip(TextView chip, boolean active) {
        if (active) {
            chip.setTextColor(Color.parseColor("#00FF9D"));
            chip.setBackground(makeCard("#08221B", "#00FF9D", 1, 10));
        } else {
            chip.setTextColor(Color.parseColor("#FF2A55"));
            chip.setBackground(makeCard("#240A12", "#FF2A55", 1, 10));
        }
    }

    private void refreshStatusChips() {
        boolean accActive = (JarvisService.getInstance() != null);
        boolean overlayActive = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean micActive = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean contactsActive = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;

        styleChip(chipAccessibility, accActive);
        styleChip(chipOverlay, overlayActive);
        styleChip(chipMic, micActive);
        styleChip(chipContacts, contactsActive);
    }

    private void checkInitialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.CAMERA
            };
            requestPermissions(perms, REQ_PERMISSIONS);
        }
    }

    private void sendMissionCommand(String cmd) {
        String apiKey = prefs.getString("gemini_api_key", "").trim();
        if (TextUtils.isEmpty(apiKey)) {
            appendLog("JARVIS", "Kanka önce sağ üstten AYARLAR'a girip Gemini API Key yapıştır ki beynim çalışsın!");
            showSettingsDialog();
            return;
        }
        JarvisService svc = JarvisService.getInstance();
        if (svc == null) {
            appendLog("JARVIS", "Otonom motor kapalı Ali! Yukarıdaki kırmızı OTONOM butonuna basıp Erişilebilirlik iznini aç.");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        appendLog("ALI", cmd);
        svc.executeMission(cmd);
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#0B1120"));
        layout.setPadding(dp(18), dp(16), dp(18), dp(16));

        TextView title = new TextView(this);
        title.setText("J.A.R.V.I.S. ÇEKİRDEK AYARLARI");
        title.setTextColor(Color.parseColor("#00F0FF"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(12));
        layout.addView(title);

        TextView lblKey = new TextView(this);
        lblKey.setText("Gemini API Key:");
        lblKey.setTextColor(Color.parseColor("#94A3B8"));
        lblKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        layout.addView(lblKey);

        final EditText etKey = new EditText(this);
        etKey.setText(prefs.getString("gemini_api_key", ""));
        etKey.setHint("AIzaSy...");
        etKey.setHintTextColor(Color.parseColor("#475569"));
        etKey.setTextColor(Color.WHITE);
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        etKey.setBackground(makeCard("#111827", "#334155", 1, 10));
        etKey.setPadding(dp(10), dp(8), dp(10), dp(8));
        layout.addView(etKey);

        TextView lblRepo = new TextView(this);
        lblRepo.setText("GitHub Repo (OTA Güncelleme):");
        lblRepo.setTextColor(Color.parseColor("#94A3B8"));
        lblRepo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        lblRepo.setPadding(0, dp(10), 0, 0);
        layout.addView(lblRepo);

        final EditText etRepo = new EditText(this);
        etRepo.setText(prefs.getString("github_repo", "aliibkl77/aliibkl77-jarvis-ai"));
        etRepo.setTextColor(Color.WHITE);
        etRepo.setBackground(makeCard("#111827", "#334155", 1, 10));
        etRepo.setPadding(dp(10), dp(8), dp(10), dp(8));
        layout.addView(etRepo);

        final Switch swWake = new Switch(this);
        swWake.setText("Sürekli 'Hey Jarvis' Dinleme Modu");
        swWake.setTextColor(Color.parseColor("#E2E8F0"));
        swWake.setChecked(prefs.getBoolean("wake_word_enabled", false));
        swWake.setPadding(0, dp(12), 0, dp(12));
        layout.addView(swWake);

        Button btnSave = new Button(this);
        btnSave.setText("KAYDET VE AKTİFLEŞTİR");
        btnSave.setTextColor(Color.parseColor("#060913"));
        btnSave.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnSave.setBackground(makeCard("#00F0FF", null, 0, 12));

        builder.setView(layout);
        final AlertDialog dialog = builder.create();

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit()
                        .putString("gemini_api_key", etKey.getText().toString().trim())
                        .putString("github_repo", etRepo.getText().toString().trim())
                        .putBoolean("wake_word_enabled", swWake.isChecked())
                        .apply();
                dialog.dismiss();
                Toast.makeText(MainActivity.this, "Çekirdek ayarları kaydedildi Ali!", Toast.LENGTH_SHORT).show();
                JarvisService svc = JarvisService.getInstance();
                if (svc != null) {
                    svc.onPreferencesUpdated();
                }
            }
        });
        layout.addView(btnSave);
        dialog.show();
    }

    @Override
    public void onStateChanged(final int state, final String mainText, final String subText) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (reactorView != null) reactorView.setState(state);
                if (tvStatusMain != null) {
                    tvStatusMain.setText(mainText);
                    int c = (state == ArcReactorView.STATE_AUTONOMOUS) ? Color.parseColor("#FF9500") :
                            (state == ArcReactorView.STATE_LISTENING) ? Color.parseColor("#00FF9D") :
                            (state == ArcReactorView.STATE_ERROR) ? Color.parseColor("#FF2A55") :
                            Color.parseColor("#00F0FF");
                    tvStatusMain.setTextColor(c);
                }
                if (tvStatusSub != null) tvStatusSub.setText(subText);
                refreshStatusChips();
            }
        });
    }

    @Override
    public void onLogAdded(final String sender, final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                appendLog(sender, message);
            }
        });
    }

    private void appendLog(String sender, String message) {
        if (chatContainer == null) return;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowLp.bottomMargin = dp(8);

        TextView tvHeader = new TextView(this);
        tvHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvHeader.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        TextView tvBody = new TextView(this);
        tvBody.setText(message);
        tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvBody.setPadding(dp(10), dp(8), dp(10), dp(8));

        if ("ALI".equals(sender)) {
            row.setGravity(Gravity.END);
            tvHeader.setText("ALİ");
            tvHeader.setTextColor(Color.parseColor("#00F0FF"));
            tvHeader.setGravity(Gravity.END);
            tvBody.setTextColor(Color.parseColor("#F8FAFC"));
            tvBody.setBackground(makeCard("#0D233A", "#00F0FF", 1, 14));
        } else if ("STEP".equals(sender)) {
            row.setGravity(Gravity.START);
            tvHeader.setText("OTONOM TELEMETRİ");
            tvHeader.setTextColor(Color.parseColor("#FF9500"));
            tvBody.setTextColor(Color.parseColor("#FDE68A"));
            tvBody.setTypeface(Typeface.MONOSPACE);
            tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tvBody.setBackground(makeCard("#1C1508", "#FF9500", 1, 10));
        } else {
            row.setGravity(Gravity.START);
            tvHeader.setText("J.A.R.V.I.S.");
            tvHeader.setTextColor(Color.parseColor("#38BDF8"));
            tvBody.setTextColor(Color.parseColor("#E2E8F0"));
            tvBody.setBackground(makeCard("#0F172A", "#334155", 1, 14));
        }

        row.addView(tvHeader);
        row.addView(tvBody);
        chatContainer.addView(row, rowLp);

        if (chatContainer.getChildCount() > 60) {
            chatContainer.removeViewAt(0);
        }

        chatScroll.post(new Runnable() {
            @Override
            public void run() {
                chatScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}
