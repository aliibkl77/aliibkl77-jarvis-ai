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
  
