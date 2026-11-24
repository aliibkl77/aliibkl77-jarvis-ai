package com.ali.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class JarvisService extends AccessibilityService {
    public static JarvisService instance;
    private WindowManager wm;
    private LinearLayout hudContainer;
    private ArcReactorView reactorView;
    private LinearLayout subtitleBox;
    private TextView statusBadge, subtitleTxt;

    private TextToSpeech tts;
    private SpeechRecognizer sr;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isWorking = false;
    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean wakeWordEnabled = false;
    private boolean awaitingFollowup = false;

    private List<String> chatMemory = new ArrayList<>();
    private String[] models = {"gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-flash-lite"};

    private class ArcReactorView extends View {
        private Paint pOuter, pMid, pCore, pGlow;
        private float angle = 0f;
        private int themeColor = Color.parseColor("#00F3FF");
        private float pulse = 1.0f;
        private boolean grow = true;

        public ArcReactorView(Context c) {
            super(c);
            pOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
            pOuter.setStyle(Paint.Style.STROKE);
            pOuter.setStrokeWidth(6f);

            pMid = new Paint(Paint.ANTI_ALIAS_FLAG);
            pMid.setStyle(Paint.Style.STROKE);
            pMid.setStrokeWidth(9f);

            pCore = new Paint(Paint.ANTI_ALIAS_FLAG);
            pCore.setStyle(Paint.Style.FILL);

            pGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
            pGlow.setStyle(Paint.Style.FILL);
        }

        public void setThemeColor(int color) {
            this.themeColor = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - 10f;

            pGlow.setColor(Color.argb(45, Color.red(themeColor), Color.green(themeColor), Color.blue(themeColor)));
            canvas.drawCircle(cx, cy, r * pulse, pGlow);

            pOuter.setColor(themeColor);
            RectF ovalOut = new RectF(cx - r + 6, cy - r + 6, cx + r - 6, cy + r - 6);
            for (int i = 0; i < 4; i++) {
                canvas.drawArc(ovalOut, angle + (i * 90), 58, false, pOuter);
            }

            pMid.setColor(Color.argb(210, Color.red(themeColor), Color.green(themeColor), Color.blue(themeColor)));
            RectF ovalMid = new RectF(cx - r * 0.68f, cy - r * 0.68f, cx + r * 0.68f, cy + r * 0.68f);
            for (int i = 0; i < 3; i++) {
                canvas.drawArc(ovalMid, -angle * 1.5f + (i * 120), 75, false, pMid);
            }

            pCore.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, r * 0.26f * pulse, pCore);
            pCore.setColor(themeColor);
            canvas.drawCircle(cx, cy, r * 0.16f, pCore);

            angle = (angle + 4f) % 360f;
            if (grow) {
                pulse += 0.012f;
                if (pulse >= 1.08f) grow = false;
            } else {
                pulse -= 0.012f;
                if (pulse <= 0.94f) grow = true;
            }
            postInvalidateDelayed(35);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        SharedPreferences prefs = getSharedPreferences("jarvis_prefs", MODE_PRIVATE);
        wakeWordEnabled = prefs.getBoolean("wake_mode", false);

        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int s) {
                if (s == TextToSpeech.SUCCESS) {
                    tts.setLanguage(new Locale("tr", "TR"));
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) { isSpeaking = true; }
                        @Override public void onError(String id) { isSpeaking = false; resumeWakeIfNeeded(); }
                        @Override public void onDone(String id) {
                            isSpeaking = false;
                            resumeWakeIfNeeded();
                        }
                    });
                }
            }
        });

        setupHud();
        speak("Jarvis Mark 7 aktif Ali.");
    }

    public void setWakeWordMode(boolean enabled) {
        this.wakeWordEnabled = enabled;
        if (enabled && !isListening && !isWorking && !isSpeaking) {
            startListening(true);
        } else if (!enabled && isListening) {
            stopListening();
            setHudState("#00F3FF", "HAZIR", "");
        }
    }

    private void resumeWakeIfNeeded() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (wakeWordEnabled && !isListening && !isWorking && !isSpeaking) {
                    startListening(true);
                }
            }
        }, 400);
    }

    private void setupHud() {
        if (!Settings.canDrawOverlays(this) || hudContainer != null) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        hudContainer = new LinearLayout(this);
        hudContainer.setOrientation(LinearLayout.HORIZONTAL);
        hudContainer.setGravity(Gravity.CENTER_VERTICAL);

        reactorView = new ArcReactorView(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(150, 150);
        hudContainer.addView(reactorView, rlp);

        subtitleBox = new LinearLayout(this);
        subtitleBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#D9060B19"));
        bg.setCornerRadius(26f);
        bg.setStroke(2, Color.parseColor("#4000F3FF"));
        subtitleBox.setBackground(bg);
        subtitleBox.setPadding(24, 14, 28, 14);
        subtitleBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = 14;
        hudContainer.addView(subtitleBox, slp);

        statusBadge = new TextView(this);
        statusBadge.setText("JARVIS");
        statusBadge.setTextColor(Color.parseColor("#00F3FF"));
        statusBadge.setTextSize(10);
        subtitleBox.addView(statusBadge);

        subtitleTxt = new TextView(this);
        subtitleTxt.setTextColor(Color.WHITE);
        subtitleTxt.setTextSize(13);
        subtitleTxt.setMaxWidth(560);
        subtitleBox.addView(subtitleTxt);

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 24;
        params.y = 320;

        reactorView.setOnTouchListener(new View.OnTouchListener() {
            int initX, initY;
            float touchX, touchY;
            long downTime;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = params.x; initY = params.y;
                        touchX = e.getRawX(); touchY = e.getRawY();
                        downTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initX + (int) (e.getRawX() - touchX);
                        params.y = initY + (int) (e.getRawY() - touchY);
                        wm.updateViewLayout(hudContainer, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (System.currentTimeMillis() - downTime < 220) {
                            if (isWorking) {
                                isWorking = false;
                                speak("Durdurdum Ali.");
                            } else if (isListening && !wakeWordEnabled) {
                                stopListening();
                                setHudState("#00F3FF", "HAZIR", "");
                            } else {
                                stopListening();
                                startListening(false);
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        wm.addView(hudContainer, params);
    }

    private void setHudState(final String hexColor, final String badge, final String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (reactorView != null) reactorView.setThemeColor(Color.parseColor(hexColor));
                if (subtitleBox == null) return;
                if (text == null || text.isEmpty()) {
                    subtitleBox.setVisibility(View.GONE);
                } else {
                    statusBadge.setText(badge);
                    statusBadge.setTextColor(Color.parseColor(hexColor));
                    subtitleTxt.setText(text);
                    subtitleBox.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void speak(final String txt) {
        if (txt == null || txt.isEmpty()) return;
        addMemory("Jarvis", txt);
        setHudState("#00F3FF", "JARVIS", txt);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (tts != null) {
                    Bundle params = new Bundle();
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "j_tts");
                    tts.speak(txt, TextToSpeech.QUEUE_FLUSH, params, "j_tts");
                }
            }
        });
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isWorking && !isListening) {
                    setHudState("#00F3FF", "HAZIR", wakeWordEnabled ? "Hey Jarvis bekleniyor..." : "");
                }
            }
        }, 5500);
    }

    private void stopListening() {
        isListening = false;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (sr != null) {
                    try { sr.destroy(); } catch (Exception ignored) {}
                    sr = null;
                }
            }
        });
    }

    private void startListening(final boolean passiveWake) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isWorking || isSpeaking) return;
                isListening = true;
                if (passiveWake) {
                    setHudState("#00E676", "NÖBETÇİ MOD", "Hey Jarvis bekleniyor...");
                } else {
                    setHudState("#FF2A85", "DİNLİYOR", "Seni dinliyorum Ali...");
                }

                if (sr != null) {
                    try { sr.destroy(); } catch (Exception ignored) {}
                }
                sr = SpeechRecognizer.createSpeechRecognizer(JarvisService.this);
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

                sr.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle b) {}
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float v) {}
                    @Override public void onBufferReceived(byte[] b) {}
                    @Override public void onEndOfSpeech() {}
                    @Override public void onEvent(int t, Bundle b) {}
                    @Override public void onPartialResults(Bundle b) {
                        if (!passiveWake) {
                            ArrayList<String> p = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                            if (p != null && !p.isEmpty()) setHudState("#FF2A85", "DİNLİYOR", p.get(0));
                        }
                    }
                    @Override public void onError(int err) {
                        isListening = false;
                        if (passiveWake && wakeWordEnabled) {
                            resumeWakeIfNeeded();
                        } else {
                            setHudState("#00F3FF", "HAZIR", "");
                            if (wakeWordEnabled) resumeWakeIfNeeded();
                        }
                    }
                    @Override public void onResults(Bundle res) {
                        isListening = false;
                        ArrayList<String> list = res.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (list == null || list.isEmpty()) {
                            resumeWakeIfNeeded();
                            return;
                        }
                        String heard = list.get(0).trim();
                        String lower = heard.toLowerCase(new Locale("tr", "TR"));

                        if (passiveWake && !awaitingFollowup) {
                            if (lower.contains("jarvis") || lower.contains("carvis") || lower.contains("yarvis")) {
                                String cleaned = lower.replace("hey jarvis", "").replace("jarvis", "")
                                        .replace("hey carvis", "").replace("carvis", "")
                                        .replace("hey yarvis", "").replace("yarvis", "").trim();
                                if (cleaned.length() < 2) {
                                    awaitingFollowup = true;
                                    speak("Efendim Ali?");
                                    mainHandler.postDelayed(new Runnable() {
                                        @Override public void run() { startListening(false); }
                                    }, 1100);
                                } else {
                                    executeGoal(cleaned);
                                }
                            } else {
                                resumeWakeIfNeeded();
                            }
                        } else {
                            awaitingFollowup = false;
                            executeGoal(heard);
                        }
                    }
                });
                sr.startListening(i);
            }
        });
    }

    private void addMemory(String role, String msg) {
        chatMemory.add(role + ": " + msg);
        if (chatMemory.size() > 12) chatMemory.remove(0);
    }

    public void executeGoal(final String goal) {
        stopListening();
        isWorking = true;
        addMemory("Ali", goal);
        setHudState("#FFAE00", "ANALİZ EDİLİYOR", goal);

        new Thread(new Runnable() {
            @Override
            public void run() {
                if (handleSystemAndLocal(goal)) {
                    isWorking = false;
                    return;
                }

                boolean needsVision = shouldCaptureScreen(goal);
                String base64Img = needsVision ? captureScreenBase64() : null;

                List<String> stepHistory = new ArrayList<>();
                for (int step = 0; step < 7 && isWorking; step++) {
                    String ui = dumpUiTree();
                    if (step > 0 && needsVision) {
                        base64Img = captureScreenBase64();
                    }
                    JSONObject res = askGemini(goal, stepHistory.toString(), ui, base64Img);
                    String act = res.optString("action", "done");
                    String thought = res.optString("thought", "");
                    stepHistory.add(act + ":" + thought);
                    if (!thought.isEmpty()) setHudState("#FFAE00", "UYGULUYOR", thought);

                    try {
                        if ("launch".equals(act)) {
                            if (!launchApp(res.optString("target", ""))) performGlobalAction(GLOBAL_ACTION_HOME);
                            Thread.sleep(1200);
                        } else if ("tap".equals(act)) {
                            tap(res.optInt("x", 500), res.optInt("y", 500));
                            Thread.sleep(650);
                        } else if ("swipe".equals(act)) {
                            swipe(res.optInt("x", 500), res.optInt("y", 1500), res.optInt("x2", 500), res.optInt("y2", 500));
                            Thread.sleep(500);
                        } else if ("text".equals(act)) {
                            inputText(res.optString("text", ""));
                            Thread.sleep(500);
                        } else if ("back".equals(act)) {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            Thread.sleep(500);
                        } else if ("home".equals(act)) {
                            performGlobalAction(GLOBAL_ACTION_HOME);
                            Thread.sleep(500);
                        } else if ("system".equals(act)) {
                            executeSystemCmd(res.optString("target", ""), res.optString("text", ""));
                        }
                    } catch (Exception ignored) {}

                    if ("done".equals(act) || res.optBoolean("done_after", false)) {
                        speak(res.optString("reply", "Tamamdir Ali."));
                        break;
                    }
                }
                isWorking = false;
            }
        }).start();
    }

    private boolean shouldCaptureScreen(String goal) {
        String g = goal.toLowerCase(new Locale("tr", "TR"));
        String[] keys = {"ekran", "gör", "bak", "oku", "çevir", "ne yazıyor", "bu ne", "fotoğraf", "resim", "oyun", "nerede", "hangi"};
        for (String k : keys) {
            if (g.contains(k)) return true;
        }
        return false;
    }

    private String captureScreenBase64() {
        if (Build.VERSION.SDK_INT < 30) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> b64Ref = new AtomicReference<>(null);

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (hudContainer != null) hudContainer.setVisibility(View.INVISIBLE);
            }
        });
        try { Thread.sleep(120); } catch (Exception ignored) {}

        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, new Executor() {
                @Override public void execute(Runnable r) { new Thread(r).start(); }
            }, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult sr) {
                    try {
                        HardwareBuffer hb = sr.getHardwareBuffer();
                        Bitmap hwBmp = Bitmap.wrapHardwareBuffer(hb, sr.getColorSpace());
                        if (hwBmp != null) {
                            Bitmap soft = hwBmp.copy(Bitmap.Config.ARGB_8888, false);
                            hwBmp.recycle();
                            int w = soft.getWidth();
                            int h = soft.getHeight();
                            int targetW = 720;
                            int targetH = (int) ((float) h * targetW / w);
                            Bitmap scaled = Bitmap.createScaledBitmap(soft, targetW, targetH, true);
                            soft.recycle();
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                            scaled.recycle();
                            b64Ref.set(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP));
                        }
                        hb.close();
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                }
                @Override
                public void onFailure(int errorCode) {
                    latch.countDown();
                }
            });
            latch.await(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (hudContainer != null) hudContainer.setVisibility(View.VISIBLE);
            }
        });
        return b64Ref.get();
    }

    private boolean handleSystemAndLocal(String goal) {
        String g = goal.toLowerCase(new Locale("tr", "TR")).trim();

        if (g.contains("sürekli dinlemeyi aç") || g.contains("nöbetçi modunu aç")) {
            getSharedPreferences("jarvis_prefs", MODE_PRIVATE).edit().putBoolean("wake_mode", true).apply();
            setWakeWordMode(true);
            speak("Sürekli dinleme aktif Ali. Seslenmen yeterli.");
            return true;
        }
        if (g.contains("sürekli dinlemeyi kapat") || g.contains("nöbetçi modunu kapat")) {
            getSharedPreferences("jarvis_prefs", MODE_PRIVATE).edit().putBoolean("wake_mode", false).apply();
            setWakeWordMode(false);
            speak("Sürekli dinlemeyi kapattım Ali.");
            return true;
        }

        if (g.contains("fener") || g.contains("ışığı") || g.contains("flaş")) {
            boolean turnOn = !g.contains("kapat") && !g.contains("söndür");
            toggleFlashlight(turnOn);
            speak(turnOn ? "El fenerini açtım Ali." : "El fenerini kapattım Ali.");
            return true;
        }

        if (g.contains("sesi fulle") || g.contains("sesi aç") || g.contains("sesi yükselt")) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (g.contains("fulle")) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI);
                speak("Sesi sonuna kadar açtım Ali.");
            } else {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                speak("Sesi yükselttim.");
            }
            return true;
        }
        if (g.contains("sesi kıs") || g.contains("sesi azalt") || g.contains("sessize al")) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (g.contains("sessize")) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI);
                speak("Sesi kapattım.");
            } else {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                speak("Sesi kıstım.");
            }
            return true;
        }

        if (g.contains("parlaklığı")) {
            if (Settings.System.canWrite(this)) {
                int val = g.contains("fulle") || g.contains("aç") ? 255 : (g.contains("kıs") ? 35 : 130);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, val);
                speak("Ekran parlaklığını ayarladım Ali.");
                return true;
            }
        }

        if (g.contains(" ve ") || g.contains("sonra") || g.contains("yaz") || g.contains("ara") || g.contains("kur")) return false;
        if (g.equals("ana ekrana dön") || g.equals("ana ekran")) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            speak("Ana ekrana döndüm Ali.");
            return true;
        }
        if (g.equals("geri") || g.equals("geri git") || g.equals("geri dön")) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            speak("Geri geldim.");
            return true;
        }
        if (g.contains("aç") || g.contains("gir")) {
            String[] apps = {"youtube", "whatsapp", "instagram", "chrome", "spotify", "telegram", "wifi", "wi-fi", "bluetooth", "ayarlar", "kamera"};
            for (String a : apps) {
                if (g.contains(a) && launchApp(a)) {
                    speak(a.toUpperCase() + " açılıyor Ali.");
                    return true;
                }
            }
        }
        return false;
    }

    private void executeSystemCmd(String cmd, String param) {
        try {
            if ("flashlight_on".equals(cmd)) toggleFlashlight(true);
            else if ("flashlight_off".equals(cmd)) toggleFlashlight(false);
            else if ("alarm".equals(cmd)) {
                String[] parts = param.split(":");
                Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
                i.putExtra(AlarmClock.EXTRA_HOUR, Integer.parseInt(parts[0].trim()));
                i.putExtra(AlarmClock.EXTRA_MINUTES, parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0);
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } else if ("call".equals(cmd)) {
                Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + param.replaceAll("[^0-9+]", "")));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } else if ("whatsapp".equals(cmd)) {
                String[] p = param.split("\\|", 2);
                String url = "https://api.whatsapp.com/send?phone=" + p[0].trim() + "&text=" + URLEncoder.encode(p.length > 1 ? p[1].trim() : "", "UTF-8");
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Exception ignored) {}
    }

    private void toggleFlashlight(boolean state) {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String camId = cm.getCameraIdList()[0];
            cm.setTorchMode(camId, state);
        } catch (Exception ignored) {}
    }

    private boolean launchApp(String target) {
        String t = target.toLowerCase(new Locale("tr", "TR")).trim();
        Intent intent = null;
        if (t.contains("wifi") || t.contains("wi-fi")) intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        else if (t.contains("bluetooth")) intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        else if (t.contains("ayar")) intent = new Intent(Settings.ACTION_SETTINGS);
        else if (t.contains("kamera")) intent = new Intent("android.media.action.IMAGE_CAPTURE");

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        }
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String label = ri.loadLabel(pm).toString().toLowerCase(new Locale("tr", "TR"));
            String pkg = ri.activityInfo.packageName.toLowerCase();
            if (label.contains(t) || pkg.contains(t)) {
                Intent launch = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                    return true;
                }
            }
        }
        return false;
    }

    private String dumpUiTree() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        traverse(root, sb, 0);
        return sb.toString();
    }

    private void traverse(AccessibilityNodeInfo node, StringBuilder sb, int count) {
        if (node == null || sb.length() > 3500) return;
        CharSequence txt = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = (txt != null && txt.length() > 0) ? txt.toString() : (desc != null ? desc.toString() : "");
        if (label.isEmpty() && node.isClickable() && node.getViewIdResourceName() != null) {
            String id = node.getViewIdResourceName();
            int idx = id.indexOf('/');
            label = "[ikon:" + (idx > 0 ? id.substring(idx + 1) : id) + "]";
        }
        if (!label.isEmpty()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.width() > 0 && r.height() > 0) {
                sb.append("\"").append(label.replace("\n", " ")).append("\" -> (")
                  .append(r.centerX()).append(",").append(r.centerY()).append(")\n");
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            traverse(node.getChild(i), sb, count + 1);
        }
    }

    private void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
        dispatchGesture(b.build(), null, null);
    }

    private void swipe(int x1, int y1, int x2, int y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 220));
        dispatchGesture(b.build(), null, null);
    }

    private void inputText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
    }

    private JSONObject askGemini(String goal, String history, String ui, String base64Img) {
        SharedPreferences prefs = getSharedPreferences("jarvis_prefs", MODE_PRIVATE);
        String apiKey = prefs.getString("api_key", "");
        String liveBrain = prefs.getString("live_brain", "Sen Ali'nin kisisel yapay zeka asistani JARVIS'sin. Samimi, zeki ve kisa Turkce konus.");

        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        String prompt = liveBrain + "\n" +
                "Ekran: " + w + "x" + h + ".\n" +
                "Sohbet gecmisi: " + chatMemory.toString() + "\n" +
                "Su anki istek: " + goal + "\n" +
                "Onceki adimlar: " + history + "\n" +
                "Ekrandaki ogeler:\n" + ui + "\n" +
                "Sadece tek bir JSON nesnesi don:\n" +
                "{\"thought\":\"kisa\",\"action\":\"launch|tap|swipe|text|back|home|system|done\",\"target\":\"\",\"x\":500,\"y\":500,\"x2\":500,\"y2\":200,\"text\":\"\",\"done_after\":false,\"reply\":\"Ali'ye sesli soylenecek kisa net Turkce cevap\"}\n" +
                "Kurallar:\n" +
                "1) Eger genel soru, sohbet, ceviri veya ekran yorumu ise action:'done', done_after:true yap ve cevabi reply alanina yaz.\n" +
                "2) Alarm kurmak icin action:'system', target:'alarm', text:'08:30', done_after:true kullan.\n" +
                "3) Numara aramak icin action:'system', target:'call', text:'05xxxxxxxxx', done_after:true kullan.\n" +
                "4) WhatsApp mesaji icin action:'system', target:'whatsapp', text:'905xxxxxxxxx|mesaj', done_after:true kullan.\n" +
                "5) Uygulama acmak icin action:'launch', target:'uygulama_adi' kullan.";

        for (String m : models) {
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent?key=" + apiKey);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);

                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("text", prompt));
                if (base64Img != null) {
                    JSONObject inline = new JSONObject().put("mime_type", "image/jpeg").put("data", base64Img);
                    parts.put(new JSONObject().put("inline_data", inline));
                }

                JSONObject content = new JSONObject().put("parts", parts);
                JSONObject cfg = new JSONObject().put("response_mime_type", "application/json").put("temperature", 0.2);
                if (m.contains("2.5")) cfg.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
                JSONObject body = new JSONObject().put("contents", new JSONArray().put(content)).put("generationConfig", cfg);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JSONObject resp = new JSONObject(sb.toString());
                    String raw = resp.getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
                    if (raw.trim().startsWith("[")) return new JSONArray(raw).getJSONObject(0);
                    return new JSONObject(raw);
                }
            } catch (Exception ignored) {}
        }
        try {
            return new JSONObject().put("action", "done").put("reply", "Su an baglanti kuramadim Ali.");
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        super.onDestroy();
        instance = null;
        stopListening();
        if (wm != null && hudContainer != null) wm.removeView(hudContainer);
    }
}
