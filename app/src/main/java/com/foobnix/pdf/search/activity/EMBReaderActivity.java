package com.foobnix.pdf.search.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.pdf.info.wrapper.MagicHelper;
import com.foobnix.sys.ImageExtractor;
import com.foobnix.tts.EMBChapterExtractor;
import com.foobnix.tts.SynthesisQueue;
import com.foobnix.tts.TTSEngine;
import com.foobnix.tts.TTSNotification;
import com.foobnix.tts.TTSService;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.core.codec.OutlineLink;

import java.util.ArrayList;
import java.util.List;

/**
 * EMBReaderActivity — chapter-as-article TTS reading view.
 *
 * FIXES vs v1
 * -----------
 * 1. AUDIO OVERLAP: TTSService is explicitly stopped before SynthesisQueue starts.
 *    In v1, TTSService remained active and played through the Android TTS engine
 *    simultaneously with SynthesisQueue's synthesizeToFile calls, causing two audio
 *    streams to mix. Fix: send TTS_STOP_DESTROY in launch() before startActivity().
 *
 * 2. CONTROLS CENTRED: weightSum=2 layout (same as tts_mp3_line.xml).
 *
 * 3. THEME: reads MagicHelper.getBgColor() / getTextColor() + BookCSS.fontSizeSp
 *    and passes ThemeParams to EMBChapterExtractor, which injects them into the CSS.
 *
 * 4. SETTINGS: ⚙ button toggles an inline panel with speed/pitch seekbars and
 *    the "open books in Article View by default" switch. Changes take effect live.
 *
 * 5. DEFAULT MODE: AppSP.embModeDefault flag. When true, DocumentWrapperUI.onResume()
 *    auto-launches this activity. The flag is toggled via the settings panel here.
 *
 * LAUNCH
 * ------
 *   EMBReaderActivity.launch(dc);          // from DocumentController context
 *
 * MANIFEST entry required (add inside <application>):
 *   <activity
 *       android:name=".pdf.search.activity.EMBReaderActivity"
 *       android:configChanges="orientation|screenSize|keyboardHidden"
 *       android:windowSoftInputMode="adjustNothing"
 *       android:exported="false" />
 */
@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility", "UseSwitchCompatOrMaterialCode"})
public class EMBReaderActivity extends Activity {

    // -------------------------------------------------------------------------
    // Intent extras
    // -------------------------------------------------------------------------

    public static final String EXTRA_PATH   = "emb_path";
    public static final String EXTRA_PAGE   = "emb_page";    // 0-indexed
    public static final String EXTRA_PARAG  = "emb_parag";   // sentence index to resume from
    public static final String EXTRA_WIDTH  = "emb_width";
    public static final String EXTRA_HEIGHT = "emb_height";
    public static final String EXTRA_FONT   = "emb_font";    // sp

    private static final String TAG = "EMBReaderActivity";

    /**
     * Set to true while EMBReaderActivity is in the foreground so that
     * DocumentWrapperUI.onResume() doesn't re-launch it when we return.
     */
    public static volatile boolean isActive = false;

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    private WebView   webView;
    private View      settingsPanel;
    private ImageView btnSettings;
    private ImageView btnPrev;
    private ImageView btnPlayPause;
    private ImageView btnNext;
    private ImageView btnClose;
    private TextView  tvProgress;
    private SeekBar   seekSpeed;
    private SeekBar   seekPitch;
    private TextView  tvSpeed;
    private TextView  tvPitch;
    private Switch    switchDefault;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private EMBChapterExtractor.ChapterContent chapter;
    private SynthesisQueue synthesisQueue;

    private volatile int  currentSentenceIdx = 0;
    private volatile boolean isPlaying        = false;
    private volatile boolean pageLoaded       = false; // guard: only start playback once
    private volatile boolean isDestroyed      = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Launch helpers
    // -------------------------------------------------------------------------

    /**
     * Launch from a DocumentController.
     * Stops TTSService first to prevent audio overlap.
     */
    public static void launch(DocumentController dc) {
        Context ctx = dc.getActivity();
        if (ctx == null) return;

        // FIX #1 — stop the old TTS playback engine before starting synthesis
        ctx.startService(new Intent(TTSNotification.TTS_STOP_DESTROY,
                null, ctx, TTSService.class));

        int page  = dc.getCurentPageFirst1() - 1; // 0-indexed
        int parag = AppSP.get().lastBookParagraph;

        Intent intent = new Intent(ctx, EMBReaderActivity.class);
        intent.putExtra(EXTRA_PATH,   dc.getCurrentBook().getPath());
        intent.putExtra(EXTRA_PAGE,   page);
        intent.putExtra(EXTRA_PARAG,  parag);
        intent.putExtra(EXTRA_WIDTH,  dc.getBookWidth());
        intent.putExtra(EXTRA_HEIGHT, dc.getBookHeight());
        intent.putExtra(EXTRA_FONT,   BookCSS.get().fontSizeSp);
        ctx.startActivity(intent);
    }

    /** Launch with explicit parameters (from notification, default-mode auto-open, etc.). */
    public static void launch(Context ctx, String path, int page, int parag,
                              int width, int height, int font) {
        ctx.startService(new Intent(TTSNotification.TTS_STOP_DESTROY,
                null, ctx, TTSService.class));

        Intent intent = new Intent(ctx, EMBReaderActivity.class);
        intent.putExtra(EXTRA_PATH,   path);
        intent.putExtra(EXTRA_PAGE,   page);
        intent.putExtra(EXTRA_PARAG,  parag);
        intent.putExtra(EXTRA_WIDTH,  width);
        intent.putExtra(EXTRA_HEIGHT, height);
        intent.putExtra(EXTRA_FONT,   font);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emb_reader);

        webView        = findViewById(R.id.emb_webview);
        settingsPanel  = findViewById(R.id.emb_settings_panel);
        btnSettings    = findViewById(R.id.emb_btn_settings);
        btnPrev        = findViewById(R.id.emb_btn_prev);
        btnPlayPause   = findViewById(R.id.emb_btn_play_pause);
        btnNext        = findViewById(R.id.emb_btn_next);
        btnClose       = findViewById(R.id.emb_btn_close);
        tvProgress     = findViewById(R.id.emb_tv_progress);
        seekSpeed      = findViewById(R.id.emb_seek_speed);
        seekPitch      = findViewById(R.id.emb_seek_pitch);
        tvSpeed        = findViewById(R.id.emb_tv_speed);
        tvPitch        = findViewById(R.id.emb_tv_pitch);
        switchDefault  = findViewById(R.id.emb_switch_default);

        applyThemeToBar();
        setupWebView();
        setupControls();
        setupSettingsPanel();
        loadChapterAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActive = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActive = false;
        if (synthesisQueue != null && isPlaying) {
            synthesisQueue.pause();
            isPlaying = false;
            updatePlayPauseIcon();
        }
    }

    @Override
    protected void onDestroy() {
        isDestroyed = true;
        isActive    = false;
        if (synthesisQueue != null) synthesisQueue.stop();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // Theme — FIX #3
    // -------------------------------------------------------------------------

    /** Tint the control bar icons to match the current app theme. */
    private void applyThemeToBar() {
        int tint  = MagicHelper.getTintColor();
        int alpha = 230;
        TintUtil.setTintImageWithAlpha(btnSettings,  tint, alpha);
        TintUtil.setTintImageWithAlpha(btnPrev,      tint, alpha);
        TintUtil.setTintImageWithAlpha(btnPlayPause, tint, alpha);
        TintUtil.setTintImageWithAlpha(btnNext,      tint, alpha);
        TintUtil.setTintImageWithAlpha(btnClose,     tint, alpha);
    }

    /**
     * Build ThemeParams from the live AppState / BookCSS values.
     * Called on the background thread just before HTML generation.
     */
    private EMBChapterExtractor.ThemeParams buildTheme() {
        try {
            int bg   = MagicHelper.getBgColor();
            int text = MagicHelper.getTextColor();

            // Highlight: warm amber on light themes, muted amber on dark
            boolean isDark = !AppState.get().isDayNotInvert;
            int highlight  = isDark
                ? Color.parseColor("#5c4a00")
                : Color.parseColor("#ffe082");

            float fontSize = BookCSS.get().fontSizeSp;
            String font    = buildFontFaceCSS(BookCSS.get().normalFont);

            return new EMBChapterExtractor.ThemeParams(bg, text, highlight, fontSize, font);
        } catch (Exception e) {
            LOG.e(e);
            return EMBChapterExtractor.ThemeParams.light();
        }
    }

    /**
     * Convert a BookCSS font name/path to a CSS font-family value.
     * If it's a file path to a .ttf/.otf, we can't load it in WebView without
     * a local file:// base URL. Fall back to a serif stack in that case.
     */
    private String buildFontFaceCSS(String normalFont) {
        if (normalFont == null || normalFont.isEmpty()) {
            return "Georgia, 'Noto Serif', serif";
        }
        if (normalFont.contains("/") || normalFont.contains(".ttf") || normalFont.contains(".otf")) {
            // Custom file font — can't reference directly; use system serif
            return "Georgia, 'Noto Serif', serif";
        }
        // Named system font — wrap in quotes in case it has spaces
        return "'" + normalFont + "', Georgia, serif";
    }

    // -------------------------------------------------------------------------
    // WebView — FIX #1 guard (pageLoaded flag)
    // -------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(false);
        ws.setBuiltInZoomControls(false);
        ws.setSupportZoom(false);
        ws.setTextZoom(100);
        ws.setLoadsImagesAutomatically(false);

        webView.addJavascriptInterface(new JsBridge(), "EMB");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (isDestroyed || pageLoaded) return; // guard against double fire
                pageLoaded = true;

                // Inject tap listener
                webView.evaluateJavascript(
                    "(function(){" +
                    "document.addEventListener('click',function(e){" +
                    "var idx=getSentenceAt(e.clientX,e.clientY);" +
                    "if(idx>=0){EMB.onSentenceTapped(idx);}" +
                    "});" +
                    "})();", null);

                int fromSentence = getIntent().getIntExtra(EXTRA_PARAG, 0);
                if (chapter != null && !chapter.sentences.isEmpty()) {
                    fromSentence = Math.max(0,
                        Math.min(fromSentence, chapter.sentences.size() - 1));
                } else {
                    fromSentence = 0;
                }

                final int startIdx = fromSentence;
                // Small delay lets WebView finish rendering before we scroll/highlight
                mainHandler.postDelayed(() -> {
                    if (!isDestroyed) startPlayback(startIdx);
                }, 350);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Controls — FIX #2 (centred) handled in XML; wiring here
    // -------------------------------------------------------------------------

    private void setupControls() {
        btnPrev.setOnClickListener(v ->
            seekToSentence(Math.max(0, currentSentenceIdx - 1)));

        btnPlayPause.setOnClickListener(v -> {
            if (isPlaying) pausePlayback();
            else           resumePlayback();
        });

        btnNext.setOnClickListener(v -> {
            if (chapter == null) return;
            seekToSentence(Math.min(chapter.sentences.size() - 1, currentSentenceIdx + 1));
        });

        btnClose.setOnClickListener(v -> finish());

        btnSettings.setOnClickListener(v -> {
            boolean showing = settingsPanel.getVisibility() == View.VISIBLE;
            settingsPanel.setVisibility(showing ? View.GONE : View.VISIBLE);
        });
    }

    // -------------------------------------------------------------------------
    // Settings panel — FIX #4 + FIX #5
    // -------------------------------------------------------------------------

    private void setupSettingsPanel() {
        // Initialise from live AppState values
        float speed = AppState.get().ttsSpeed;
        float pitch = AppState.get().ttsPitch;

        // SeekBar range 0–20 maps to TTS speed 0.5×–2.5× (step 0.1)
        seekSpeed.setProgress(speedToProgress(speed));
        seekPitch.setProgress(speedToProgress(pitch));
        tvSpeed.setText(formatRate(speed));
        tvPitch.setText(formatRate(pitch));

        switchDefault.setChecked(AppSP.get().embModeDefault);

        seekSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                float val = progressToSpeed(progress);
                AppState.get().ttsSpeed = val;
                tvSpeed.setText(formatRate(val));
                // Apply to TTS engine live so the next sentence uses the new rate
                try {
                    if (TTSEngine.get().isInit()) TTSEngine.get().getTTS().setSpeechRate(val);
                } catch (Exception ignored) {}
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                // Restart synthesis from current position with new speed
                if (isPlaying) {
                    int idx = currentSentenceIdx;
                    pausePlayback();
                    seekToSentence(idx);
                }
            }
        });

        seekPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                float val = progressToSpeed(progress);
                AppState.get().ttsPitch = val;
                tvPitch.setText(formatRate(val));
                try {
                    if (TTSEngine.get().isInit()) TTSEngine.get().getTTS().setPitch(val);
                } catch (Exception ignored) {}
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (isPlaying) {
                    int idx = currentSentenceIdx;
                    pausePlayback();
                    seekToSentence(idx);
                }
            }
        });

        switchDefault.setOnCheckedChangeListener((CompoundButton cb, boolean checked) -> {
            AppSP.get().embModeDefault = checked;
            AppSP.get().save();
        });
    }

    private float progressToSpeed(int progress) {
        // 0→0.5, 10→1.0, 20→2.5
        return 0.5f + progress * 0.1f;
    }

    private int speedToProgress(float speed) {
        return Math.max(0, Math.min(20, Math.round((speed - 0.5f) / 0.1f)));
    }

    private String formatRate(float r) {
        return String.format("%.1f×", r);
    }

    // -------------------------------------------------------------------------
    // Chapter loading
    // -------------------------------------------------------------------------

    private void loadChapterAsync() {
        final String path   = getIntent().getStringExtra(EXTRA_PATH);
        final int    page   = getIntent().getIntExtra(EXTRA_PAGE,   0);
        final int    width  = getIntent().getIntExtra(EXTRA_WIDTH,  1080);
        final int    height = getIntent().getIntExtra(EXTRA_HEIGHT, 1920);
        final int    font   = getIntent().getIntExtra(EXTRA_FONT,   18);

        if (path == null || path.isEmpty()) {
            Toast.makeText(this, "No book path", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Read theme on main thread before jumping to background
        final EMBChapterExtractor.ThemeParams theme = buildTheme();

        new Thread(() -> {
            CodecDocument dc = null;
            try {
                dc = ImageExtractor.singleCodecContext(path, "");
                if (dc == null) { postError("Cannot open book"); return; }
                dc.getPageCount(width, height, font);

                List<OutlineLinkWrapper> outline = convertOutline(dc.getOutline());
                final EMBChapterExtractor.ChapterContent result =
                    EMBChapterExtractor.extract(dc, page, outline, theme);

                if (isDestroyed) return;
                chapter = result;

                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    updateProgress(0);
                    webView.loadDataWithBaseURL(
                        "file:///android_asset/",
                        result.html, "text/html", "utf-8", null);
                });

            } catch (Exception e) {
                LOG.e(e);
                postError("Error: " + e.getMessage());
            } finally {
                if (dc != null) try { dc.recycle(); } catch (Exception ignored) {}
            }
        }, "@EMB ChapterLoad").start();
    }

    private List<OutlineLinkWrapper> convertOutline(List<? extends OutlineLink> raw) {
        List<OutlineLinkWrapper> result = new ArrayList<>();
        if (raw == null) return result;
        for (OutlineLink link : raw) {
            try {
                OutlineLinkWrapper w = new OutlineLinkWrapper(
                    link.getTitle(), link.getLink(), link.getLevel(), link.linkUri);
                if (w.targetPage > 0) result.add(w);
            } catch (Exception ignored) {}
        }
        return result;
    }

    private void postError(String msg) {
        mainHandler.post(() -> {
            if (!isDestroyed) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); finish(); }
        });
    }

    // -------------------------------------------------------------------------
    // Playback control
    // -------------------------------------------------------------------------

    private void startPlayback(int fromSentence) {
        if (chapter == null || chapter.sentences.isEmpty()) return;
        fromSentence = Math.max(0, Math.min(fromSentence, chapter.sentences.size() - 1));

        if (synthesisQueue != null) synthesisQueue.stop();

        synthesisQueue = new SynthesisQueue(this, new SynthesisQueue.Callback() {
            @Override public void onSentenceStart(int idx, String text) {
                currentSentenceIdx = idx;
                isPlaying = true;
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    webView.evaluateJavascript("highlightSentence(" + idx + ")", null);
                    updateProgress(idx);
                    updatePlayPauseIcon();
                    AppSP.get().lastBookParagraph = idx;
                    AppSP.get().save();
                });
            }

            @Override public void onFinished() {
                isPlaying = false;
                mainHandler.post(() -> { if (!isDestroyed) updatePlayPauseIcon(); });
            }

            @Override public void onError(int idx, Exception e) {
                LOG.e(e, TAG, "sentence error at", idx);
            }
        });

        currentSentenceIdx = fromSentence;
        isPlaying = true;
        updatePlayPauseIcon();
        synthesisQueue.start(chapter.sentences, fromSentence);
        webView.evaluateJavascript("highlightSentence(" + fromSentence + ")", null);
    }

    private void pausePlayback() {
        if (synthesisQueue != null) synthesisQueue.pause();
        isPlaying = false;
        updatePlayPauseIcon();
    }

    private void resumePlayback() {
        if (synthesisQueue != null) {
            synthesisQueue.resume();
            isPlaying = true;
            updatePlayPauseIcon();
        } else {
            startPlayback(currentSentenceIdx);
        }
    }

    private void seekToSentence(int idx) {
        if (chapter == null || idx < 0 || idx >= chapter.sentences.size()) return;
        currentSentenceIdx = idx;
        webView.evaluateJavascript("highlightSentence(" + idx + ")", null);
        startPlayback(idx);
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void updateProgress(int idx) {
        if (chapter == null) return;
        tvProgress.setText((idx + 1) + " / " + chapter.sentences.size());
    }

    private void updatePlayPauseIcon() {
        btnPlayPause.setImageResource(
            isPlaying ? R.drawable.glyphicons_174_pause
                      : R.drawable.glyphicons_175_play);
        TintUtil.setTintImageWithAlpha(btnPlayPause, MagicHelper.getTintColor(), 230);
    }

    // -------------------------------------------------------------------------
    // JS ↔ Java bridge
    // -------------------------------------------------------------------------

    private class JsBridge {
        @JavascriptInterface
        public void onSentenceTapped(final int sentIdx) {
            mainHandler.post(() -> { if (!isDestroyed) seekToSentence(sentIdx); });
        }
    }
}
