package com.jarvis.ali.ai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class JarvisService extends AccessibilityService {

    public interface UiCallback {
        void onStateChanged(int state, String mainText, String subText);
        void onLogAdded(String sender, String message);
    }

    private static JarvisService instance;
    private static UiCallback uiCallback;

    public static JarvisService getInstance() {
        return instance;
    }

    public static void setUiCallback(UiCallback cb) {
        uiCallback = cb;
    }

    private Handler mainHandler;
    private ExecutorService executor;
    private SharedPreferences prefs;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private boolean continuousWakeMode = false;

    private volatile boolean isAutonomousRunning = false;
    private int currentState = ArcReactorView.STATE_IDLE;
    private String currentStatusText = "SİSTEM HAZIR";
    private String currentSubText = "Emrini bekliyorum Ali";

    private WindowManager windowManager;
    private LinearLayout floatingPill;
    private TextView pillText;
    private TextView pillStopBtn;
    private WindowManager.LayoutParams pillParams;

    private boolean torchOn = false;
    private String dynamicBrainRules = "";

    private static class NodeEntry {
        int index;
        AccessibilityNodeInfo node;
        String summary;
        int cx;
        int cy;
    }

    private final List<NodeEntry> lastScreenNodes = new ArrayList<NodeEntry>();

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor();
        prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE);
        continuousWakeMode = prefs.getBoolean("wake_word_enabled", false);

        initTts();
        setupFloatingPill();
        syncBrainAndCheckUpdate(false);

        updateState(ArcReactorView.STATE_IDLE, "SİSTEM HAZIR", "Mark VII Otonom Çekirdek Aktif");
        if (continuousWakeMode) {
            startListening();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        isAutonomousRunning = false;
    }

    @Override
    public void onDestroy() {
        isAutonomousRunning = false;
        if (floatingPill != null && windowManager != null) {
            try {
                windowManager.removeView(floatingPill);
            } catch (Exception ignored) {
            }
        }
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {
            }
        }
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception ignored) {
            }
        }
        instance = null;
        super.onDestroy();
    }

    public int getCurrentState() {
        return currentState;
    }

    public String getCurrentStatusText() {
        return currentStatusText;
    }

    public String getCurrentSubText() {
        return currentSubText;
    }

    public void onPreferencesUpdated() {
        continuousWakeMode = prefs.getBoolean("wake_word_enabled", false);
        if (continuousWakeMode && !isListening && !isAutonomousRunning) {
            startListening();
        }
    }

    private void updateState(int state, String mainText, String subText) {
        currentState = state;
        currentStatusText = mainText;
        currentSubText = subText;
        if (uiCallback != null) {
            uiCallback.onStateChanged(state, mainText, subText);
        }
        updateFloatingPill(state, mainText);
    }

    private void emitLog(String sender, String msg) {
        if (uiCallback != null) {
            uiCallback.onLogAdded(sender, msg);
        }
    }

    private void initTts() {
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    int res = tts.setLanguage(new Locale("tr", "TR"));
                    ttsReady = (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED);
                    tts.setSpeechRate(1.08f);
                }
            }
        });
    }

    public void speak(final String text) {
        if (TextUtils.isEmpty(text)) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (ttsReady && tts != null) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utt");
                }
            }
        });
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()
        );
    }

    private void setupFloatingPill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return;
        }
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            floatingPill = new LinearLayout(this);
            floatingPill.setOrientation(LinearLayout.HORIZONTAL);
            floatingPill.setGravity(Gravity.CENTER_VERTICAL);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#E6080E1E"));
            bg.setStroke(dp(1), Color.parseColor("#00F0FF"));
            bg.setCornerRadius(dp(20));
            floatingPill.setBackground(bg);
            floatingPill.setPadding(dp(12), dp(6), dp(10), dp(6));

            pillText = new TextView(this);
            pillText.setText("J.A.R.V.I.S.");
            pillText.setTextColor(Color.parseColor("#00F0FF"));
            pillText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pillText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            floatingPill.addView(pillText);

            pillStopBtn = new TextView(this);
            pillStopBtn.setText("DUR");
            pillStopBtn.setTextColor(Color.WHITE);
            pillStopBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            pillStopBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            GradientDrawable stopBg = new GradientDrawable();
            stopBg.setColor(Color.parseColor("#FF2A55"));
            stopBg.setCornerRadius(dp(12));
            pillStopBtn.setBackground(stopBg);
            pillStopBtn.setPadding(dp(8), dp(3), dp(8), dp(3));
            pillStopBtn.setVisibility(View.GONE);
            LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            stopLp.leftMargin = dp(8);
            pillStopBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    isAutonomousRunning = false;
                    updateState(ArcReactorView.STATE_IDLE, "GÖREV DURDURULDU", "Otonom döngü iptal edildi Ali");
                    speak("Görev durduruldu Ali.");
                }
            });
            floatingPill.addView(pillStopBtn, stopLp);

            int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            pillParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
            pillParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            pillParams.y = dp(28);

            floatingPill.setOnTouchListener(new View.OnTouchListener() {
                private int initialX, initialY;
                private float initialTouchX, initialTouchY;
                private long downTime;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialX = pillParams.x;
                            initialY = pillParams.y;
                            initialTouchX = event.getRawX();
                            initialTouchY = event.getRawY();
                            downTime = System.currentTimeMillis();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            pillParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                            pillParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                            windowManager.updateViewLayout(floatingPill, pillParams);
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (System.currentTimeMillis() - downTime < 220) {
                                toggleVoiceListening();
                            }
                            return true;
                    }
                    return false;
                }
            });

            windowManager.addView(floatingPill, pillParams);
        } catch (Exception ignored) {
        }
    }

    private void updateFloatingPill(final int state, final String text) {
        if (floatingPill == null || pillText == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    GradientDrawable bg = (GradientDrawable) floatingPill.getBackground();
                    if (state == ArcReactorView.STATE_AUTONOMOUS) {
                        bg.setStroke(dp(1), Color.parseColor("#FF9500"));
                        pillText.setTextColor(Color.parseColor("#FF9500"));
                        pillText.setText(text.length() > 34 ? text.substring(0, 34) + "..." : text);
                        pillStopBtn.setVisibility(View.VISIBLE);
                    } else if (state == ArcReactorView.STATE_LISTENING) {
                        bg.setStroke(dp(1), Color.parseColor("#00FF9D"));
                        pillText.setTextColor(Color.parseColor("#00FF9D"));
                        pillText.setText("DİNLİYORUM...");
                        pillStopBtn.setVisibility(View.GONE);
                    } else {
                        bg.setStroke(dp(1), Color.parseColor("#00F0FF"));
                        pillText.setTextColor(Color.parseColor("#00F0FF"));
                        pillText.setText("J.A.R.V.I.S.");
                        pillStopBtn.setVisibility(View.GONE);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    public void toggleVoiceListening() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isListening) {
                    stopListening();
                    updateState(ArcReactorView.STATE_IDLE, "SİSTEM HAZIR", "Dinleme durduruldu");
                } else {
                    startListening();
                }
            }
        });
    }

    private void startListening() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isAutonomousRunning) return;
                try {
                    if (speechRecognizer != null) {
                        speechRecognizer.destroy();
                    }
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(JarvisService.this);
                    speechRecognizer.setRecognitionListener(new RecognitionListener() {
                        @Override
                        public void onReadyForSpeech(Bundle params) {
                            isListening = true;
                            updateState(ArcReactorView.STATE_LISTENING, "DİNLİYORUM ALİ...", "Emrini söyle kanka");
                        }

                        @Override
                        public void onBeginningOfSpeech() {}

                        @Override
                        public void onRmsChanged(float rmsdB) {}

                        @Override
                        public void onBufferReceived(byte[] buffer) {}

                        @Override
                        public void onEndOfSpeech() {
                            isListening = false;
                        }

                        @Override
                        public void onError(int error) {
                            isListening = false;
                            if (!isAutonomousRunning) {
                                updateState(ArcReactorView.STATE_IDLE, "SİSTEM HAZIR", "Emrini bekliyorum Ali");
                                if (continuousWakeMode) {
                                    mainHandler.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (continuousWakeMode && !isAutonomousRunning) startListening();
                                        }
                                    }, 900);
                                }
                            }
                        }

                        @Override
                        public void onResults(Bundle results) {
                            isListening = false;
                            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                            if (matches != null && !matches.isEmpty()) {
                                String spoken = matches.get(0).trim();
                                String lower = spoken.toLowerCase(new Locale("tr", "TR"));
                                if (continuousWakeMode && !lower.contains("jarvis") && !lower.contains("carvis")) {
                                    startListening();
                                    return;
                                }
                                spoken = spoken.replaceAll("(?i)hey jarvis|jarvis|carvis", "").trim();
                                if (!TextUtils.isEmpty(spoken)) {
                                    emitLog("ALI", spoken);
                                    executeMission(spoken);
                                    return;
                                }
                            }
                            if (continuousWakeMode && !isAutonomousRunning) {
                                startListening();
                            } else {
                                updateState(ArcReactorView.STATE_IDLE, "SİSTEM HAZIR", "Emrini bekliyorum Ali");
                            }
                        }

                        @Override
                        public void onPartialResults(Bundle partialResults) {}

                        @Override
                        public void onEvent(int eventType, Bundle params) {}
                    });

                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
                    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                    speechRecognizer.startListening(intent);
                } catch (Exception e) {
                    isListening = false;
                    updateState(ArcReactorView.STATE_ERROR, "MİKROFON HATASI", "Mikrofon iznini kontrol et Ali");
                }
            }
        });
    }

    private void stopListening() {
        isListening = false;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
            } catch (Exception ignored) {
            }
        }
    }

    public void executeMission(final String userGoal) {
        if (handleInstantHardwareFastPath(userGoal)) {
            return;
        }

        isAutonomousRunning = false;
        stopListening();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                }
                isAutonomousRunning = true;
                runClosedLoopAgent(userGoal);
            }
        });
    }

    private boolean handleInstantHardwareFastPath(String cmd) {
        String lower = cmd.toLowerCase(new Locale("tr", "TR"));
        if (lower.equals("feneri aç") || lower.equals("fener aç") || lower.equals("ışığı aç")) {
            setFlashlight(true);
            emitLog("JARVIS", "Feneri açtım Ali.");
            speak("Feneri açtım Ali.");
            return true;
        }
        if (lower.equals("feneri kapat") || lower.equals("fener kapat") || lower.equals("ışığı kapat")) {
            setFlashlight(false);
            emitLog("JARVIS", "Feneri kapattım.");
            speak("Feneri kapattım.");
            return true;
        }
        if (lower.equals("geri git") || lower.equals("geri")) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            return true;
        }
        if (lower.equals("ana ekrana dön") || lower.equals("ana ekran")) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            return true;
        }
        return false;
    }
    
    private void runClosedLoopAgent(String userGoal) {
        String apiKey = prefs.getString("gemini_api_key", "").trim();
        if (TextUtils.isEmpty(apiKey)) {
            updateState(ArcReactorView.STATE_ERROR, "API KEY EKSİK", "Ayarlardan Gemini API Key gir Ali");
            emitLog("JARVIS", "Ayarlardan Gemini API Key girmen lazım Ali.");
            isAutonomousRunning = false;
            return;
        }

        String contactHints = resolveRelevantContacts(userGoal);
        String appHints = resolveRelevantApps(userGoal);
        List<String> stepHistory = new ArrayList<String>();

        int maxSteps = 14;
        for (int step = 1; step <= maxSteps; step++) {
            if (!isAutonomousRunning) break;

            updateState(ArcReactorView.STATE_AUTONOMOUS, "ADIM " + step + "/" + maxSteps + " ANALİZ...", userGoal);

            String screenContext = captureScreenStateBlocking();
            String prompt = buildAgentPrompt(userGoal, step, maxSteps, contactHints, appHints, stepHistory, screenContext);

            String rawJson = callGeminiJson(apiKey, prompt);
            if (!isAutonomousRunning) break;

            if (TextUtils.isEmpty(rawJson)) {
                updateState(ArcReactorView.STATE_ERROR, "BAĞLANTI HATASI", "Gemini sunucusuna ulaşılamadı");
                emitLog("JARVIS", "İnternet veya API bağlantısında kopukluk oldu kanka, tekrar dene.");
                speak("Bağlantı hatası oldu Ali.");
                break;
            }

            try {
                JSONObject obj = new JSONObject(rawJson);
                String thought = obj.optString("thought", "");
                String speech = obj.optString("speech", "");
                String action = obj.optString("action", "DONE").toUpperCase(Locale.US);
                String target = obj.optString("target", "");
                String text = obj.optString("text", "");
                boolean done = obj.optBoolean("done", false);

                if (!TextUtils.isEmpty(thought)) {
                    emitLog("STEP", "Adım " + step + " [" + action + "]: " + thought);
                    updateState(ArcReactorView.STATE_AUTONOMOUS, "ADIM " + step + ": " + action, thought);
                }

                if (!TextUtils.isEmpty(speech)) {
                    emitLog("JARVIS", speech);
                    speak(speech);
                }

                if ("DONE".equals(action)) {
                    break;
                }

                String execResult = executeSingleAction(action, target, text);
                stepHistory.add("Adım " + step + " -> " + action + "(" + target + ", '" + text + "') => " + execResult);

                if (done) {
                    break;
                }

                long waitMs = "OPEN_APP".equals(action) ? 1900L :
                              "WAIT".equals(action) ? 2000L : 1150L;
                Thread.sleep(waitMs);

            } catch (Exception e) {
                stepHistory.add("Adım " + step + " JSON ayrıştırma hatası: " + e.getMessage());
            }
        }

        isAutonomousRunning = false;
        updateState(ArcReactorView.STATE_IDLE, "SİSTEM HAZIR", "Görev tamamlandı • Emrini bekliyorum Ali");
        if (continuousWakeMode) {
            startListening();
        }
    }

    private String captureScreenStateBlocking() {
        final String[] resultHolder = new String[]{"EKRAN OKUNAMADI"};
        final CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    lastScreenNodes.clear();
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root == null) {
                        resultHolder[0] = "AKTİF PENCERE YOK (Geçiş yapılıyor)";
                        return;
                    }
                    CharSequence pkg = root.getPackageName();
                    StringBuilder sb = new StringBuilder();
                    sb.append("AÇIK UYGULAMA PAKETİ: ").append(pkg != null ? pkg.toString() : "bilinmiyor").append("\n");
                    sb.append("EKRANDAKİ ETKİLEŞİMLİ ÖĞELER:\n");

                    collectNodesRecursive(root, sb, 0);
                    resultHolder[0] = sb.toString();
                } catch (Exception e) {
                    resultHolder[0] = "EKRAN OKUMA HATASI: " + e.getMessage();
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            latch.await(2500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return resultHolder[0];
    }

    private void collectNodesRecursive(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 22 || lastScreenNodes.size() >= 85) return;

        if (node.isVisibleToUser()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() > 0 && bounds.height() > 0) {
                String text = node.getText() != null ? node.getText().toString().trim() : "";
                String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim() : "";
                String viewId = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
                if (viewId.contains("/")) {
                    viewId = viewId.substring(viewId.lastIndexOf('/') + 1);
                }
                boolean clickable = node.isClickable();
                boolean editable = node.isEditable();
                boolean scrollable = node.isScrollable();

                if (!text.isEmpty() || !desc.isEmpty() || clickable || editable || scrollable) {
                    int idx = lastScreenNodes.size();
                    String cls = node.getClassName() != null ? node.getClassName().toString() : "View";
                    if (cls.contains(".")) {
                        cls = cls.substring(cls.lastIndexOf('.') + 1);
                    }

                    StringBuilder line = new StringBuilder();
                    line.append("[").append(idx).append("] ").append(cls);
                    if (!text.isEmpty()) line.append(" text=\"").append(text.replace("\n", " ")).append("\"");
                    if (!desc.isEmpty()) line.append(" desc=\"").append(desc.replace("\n", " ")).append("\"");
                    if (!viewId.isEmpty()) line.append(" id=").append(viewId);
                    if (clickable) line.append(" [TIKLANABİLİR]");
                    if (editable) line.append(" [YAZILABİLİR]");
                    if (scrollable) line.append(" [KAYDIRILABİLİR]");
                    line.append(" (").append(bounds.centerX()).append(",").append(bounds.centerY()).append(")\n");

                    NodeEntry entry = new NodeEntry();
                    entry.index = idx;
                    entry.node = node;
                    entry.summary = line.toString();
                    entry.cx = bounds.centerX();
                    entry.cy = bounds.centerY();
                    lastScreenNodes.add(entry);
                    sb.append(entry.summary);
                }
            }
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            collectNodesRecursive(node.getChild(i), sb, depth + 1);
        }
    }

    private String executeSingleAction(final String action, final String target, final String text) {
        final String[] res = new String[]{"OK"};
        final CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if ("OPEN_APP".equals(action)) {
                        boolean launched = launchAppSmart(target);
                        res[0] = launched ? "Uygulama açıldı: " + target : "Uygulama bulunamadı: " + target;
                    } else if ("CLICK_NODE".equals(action)) {
                        int idx = Integer.parseInt(target.trim());
                        if (idx >= 0 && idx < lastScreenNodes.size()) {
                            NodeEntry entry = lastScreenNodes.get(idx);
                            boolean clicked = performSmartClick(entry);
                            res[0] = clicked ? "Tıklandı: [" + idx + "]" : "Tıklama başarısız: [" + idx + "]";
                        } else {
                            res[0] = "Geçersiz düğüm indeksi: " + target;
                        }
                    } else if ("TYPE_NODE".equals(action)) {
                        int idx = Integer.parseInt(target.trim());
                        if (idx >= 0 && idx < lastScreenNodes.size()) {
                            NodeEntry entry = lastScreenNodes.get(idx);
                            boolean typed = performSmartType(entry, text);
                            res[0] = typed ? "Yazıldı: '" + text + "'" : "Yazma başarısız";
                        } else {
                            res[0] = "Geçersiz metin kutusu indeksi: " + target;
                        }
                    } else if ("TAP_XY".equals(action)) {
                        String[] parts = target.split(",");
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        performGestureTap(x, y);
                        res[0] = "Koordinata dokunuldu: (" + x + "," + y + ")";
                    } else if ("SCROLL_DOWN".equals(action)) {
                        performSmartScroll(true);
                        res[0] = "Aşağı kaydırıldı";
                    } else if ("SCROLL_UP".equals(action)) {
                        performSmartScroll(false);
                        res[0] = "Yukarı kaydırıldı";
                    } else if ("PRESS_ENTER".equals(action)) {
                        performImeEnterOrSend();
                        res[0] = "Enter/Gönder tetiklendi";
                    } else if ("BACK".equals(action)) {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        res[0] = "Geri tuşuna basıldı";
                    } else if ("HOME".equals(action)) {
                        performGlobalAction(GLOBAL_ACTION_HOME);
                        res[0] = "Ana ekrana dönüldü";
                    } else if ("CALL_NUMBER".equals(action)) {
                        Intent callIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + target.trim()));
                        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(callIntent);
                        res[0] = "Arama başlatıldı: " + target;
                    } else if ("OPEN_URL".equals(action)) {
                        String u = target.startsWith("http") ? target : "https://" + target;
                        Intent urlIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                        urlIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(urlIntent);
                        res[0] = "URL açıldı: " + u;
                    } else if ("SYSTEM_CMD".equals(action)) {
                        handleSystemCmd(target);
                        res[0] = "Sistem komutu uygulandı: " + target;
                    } else {
                        res[0] = "Beklendi";
                    }
                } catch (Exception e) {
                    res[0] = "Hata: " + e.getMessage();
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            latch.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return res[0];
    }

    private boolean performSmartClick(NodeEntry entry) {
        AccessibilityNodeInfo current = entry.node;
        for (int i = 0; i < 5 && current != null; i++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }
        performGestureTap(entry.cx, entry.cy);
        return true;
    }

    private boolean performSmartType(NodeEntry entry, String text) {
        performSmartClick(entry);
        AccessibilityNodeInfo node = entry.node;
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("jarvis_input", text));
                return node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            }
        }
        return false;
    }

    private void performImeEnterOrSend() {
        if (Build.VERSION.SDK_INT >= 30) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused != null) {
                    focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                }
            }
        }
    }

    private void performSmartScroll(boolean forward) {
        for (NodeEntry entry : lastScreenNodes) {
            if (entry.node != null && entry.node.isScrollable()) {
                int act = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                if (entry.node.performAction(act)) return;
            }
        }
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int x = width / 2;
        int startY = forward ? (int) (height * 0.72) : (int) (height * 0.32);
        int endY = forward ? (int) (height * 0.28) : (int) (height * 0.72);
        performGestureSwipe(x, startY, x, endY, 320);
    }

    private void performGestureTap(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 60);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
    }

    private void performGestureSwipe(int x1, int y1, int x2, int y2, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
    }

    private boolean launchAppSmart(String query) {
        PackageManager pm = getPackageManager();
        if (query.contains(".")) {
            Intent direct = pm.getLaunchIntentForPackage(query.trim());
            if (direct != null) {
                direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(direct);
                return true;
            }
        }

        String qLower = query.toLowerCase(new Locale("tr", "TR")).trim();
        if (qLower.contains("whatsapp")) {
            Intent wa = pm.getLaunchIntentForPackage("com.whatsapp");
            if (wa != null) {
                wa.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(wa);
                return true;
            }
        }

        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo app : apps) {
            Intent launch = pm.getLaunchIntentForPackage(app.packageName);
            if (launch == null) continue;
            String label = pm.getApplicationLabel(app).toString().toLowerCase(new Locale("tr", "TR"));
            if (label.equals(qLower) || label.contains(qLower) || qLower.contains(label)) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return true;
            }
        }
        return false;
    }

    private void handleSystemCmd(String cmd) {
        String c = cmd.toUpperCase(Locale.US);
        if (c.contains("FLASH_ON")) setFlashlight(true);
        else if (c.contains("FLASH_OFF")) setFlashlight(false);
        else if (c.contains("VOL_MAX")) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
        } else if (c.contains("VOL_MUTE")) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        }
    }

    private void setFlashlight(boolean enable) {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm != null && cm.getCameraIdList().length > 0) {
                cm.setTorchMode(cm.getCameraIdList()[0], enable);
                torchOn = enable;
            }
        } catch (Exception ignored) {
        }
    }

    private String resolveRelevantContacts(String userGoal) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Rehber izni kapalı.";
        }
        StringBuilder sb = new StringBuilder();
        Cursor cursor = null;
        try {
            String goalLower = userGoal.toLowerCase(new Locale("tr", "TR"));
            cursor = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    null, null, null
            );
            int found = 0;
            while (cursor != null && cursor.moveToNext() && found < 6) {
                String name = cursor.getString(0);
                String num = cursor.getString(1);
                if (TextUtils.isEmpty(name)) continue;
                String nameLower = name.toLowerCase(new Locale("tr", "TR"));
                if (goalLower.contains(nameLower) || (nameLower.length() >= 3 && goalLower.contains(nameLower.substring(0, nameLower.length() - 1)))) {
                    sb.append("- Rehber Kaydı: \"").append(name).append("\" (Tel: ").append(num).append(")\n");
                    found++;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return sb.length() > 0 ? sb.toString() : "Doğrudan isim eşleşmesi bulunamadı.";
    }

    private String resolveRelevantApps(String userGoal) {
        StringBuilder sb = new StringBuilder();
        try {
            PackageManager pm = getPackageManager();
            String goalLower = userGoal.toLowerCase(new Locale("tr", "TR"));
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            int count = 0;
            for (ApplicationInfo app : apps) {
                if (pm.getLaunchIntentForPa
