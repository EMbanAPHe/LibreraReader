package com.foobnix.pdf.search.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;
import com.foobnix.pdf.info.view.DragingDialogs;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.pdf.info.wrapper.MagicHelper;
import com.foobnix.sys.ImageExtractor;
import com.foobnix.tts.EMBChapterExtractor;
import com.foobnix.tts.SynthesisQueue;
import com.foobnix.tts.TTSNotification;
import com.foobnix.tts.TTSService;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.core.codec.OutlineLink;

import java.util.ArrayList;
import java.util.List;

/**
 * EMBReaderActivity — chapter-as-article TTS reading view.
 *
 * PLAYBACK: SynthesisQueue uses speak()+QUEUE_ADD. No WAV files.
 * Sherpa-onnx receives all upcoming sentences via the TTS queue and pre-synthesises
 * them internally — the queue keeps PREFETCH_AHEAD sentences ahead of playback.
 *
 * SETTINGS: The ⚙ button opens the existing DragingDialogs.dialogTextToSpeech()
 * popup using a hidden anchor FrameLayout rooted in this Activity's window.
 * All existing TTS settings (speed, pitch, engine, voice, timer, etc.) work as-is.
 *
 * NO AUTO-PLAY: The chapter loads and highlights sentence 0, but playback only
 * starts when the user taps ▶.
 *
 * MODE INTEGRATION:
 *   - Accessible from the reading mode dialog (choose_mode_dialog.xml) on book open
 *   - Listed as "Article View" in Preferences > Single tap (PrefFragment2)
 *   - Listed in the in-book ⋮ ShareDialog menu
 *   - READING_MODE_EMB = 7 stored in AppSP.readingMode like all other modes
 */
@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
public class EMBReaderActivity extends Activity {

    // -------------------------------------------------------------------------
    // Intent extras
    // -------------------------------------------------------------------------

    public static final String EXTRA_PATH   = "emb_path";
    public static final String EXTRA_PAGE   = "emb_page";   // 0-indexed
    public static final String EXTRA_PARAG  = "emb_parag";  // sentence to resume from
    public static final String EXTRA_WIDTH  = "emb_width";
    public static final String EXTRA_HEIGHT = "emb_height";
    public static final String EXTRA_FONT   = "emb_font";

    private static final String TAG = "EMBReaderActivity";

    /**
     * Static reference to the last DocumentController used to launch us.
     * Used to open the TTS settings popup. Set in launch(DocumentController).
     * May be null when launched from the library / mode dialog.
     */
    public static volatile DocumentController lastDC = null;

    /**
     * True while EMBReaderActivity is in the foreground.
     * Not needed for auto-launch anymore (replaced by READING_MODE_EMB), but
     * kept as a safety flag.
     */
    public static volatile boolean isActive = false;

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    private WebView   webView;
    private ImageView btnSettings;
    private ImageView btnPrev;
    private ImageView btnPlayPause;
    private ImageView btnNext;
    private ImageView btnClose;
    private TextView  tvProgress;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private EMBChapterExtractor.ChapterContent chapter;
    private SynthesisQueue synthesisQueue;

    private volatile int     currentSentenceIdx = 0;
    private volatile boolean isPlaying          = false;
    private volatile boolean pageLoaded         = false;
    private volatile boolean isDestroyed        = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Launch helpers
    // -------------------------------------------------------------------------

    /** Launch from a DocumentController (in-book button / ShareDialog). */
    public static void launch(DocumentController dc) {
        Context ctx = dc.getActivity();
        if (ctx == null) return;

        lastDC = dc;

        // Stop TTSService to prevent audio overlap
        try {
            ctx.startService(new Intent(TTSNotification.TTS_STOP_DESTROY,
                    null, ctx, TTSService.class));
        } catch (Exception ignored) {}

        int page  = dc.getCurentPageFirst1() - 1;
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

    /**
     * Launch with explicit parameters — used by ExtUtils.showDocumentInner()
     * when READING_MODE_EMB is selected (library / mode dialog).
     * No DocumentController is available in that path, so lastDC may be null.
     */
    public static void launch(Context ctx, String path, int page, int parag,
                              int width, int height, int font) {
        try {
            ctx.startService(new Intent(TTSNotification.TTS_STOP_DESTROY,
                    null, ctx, TTSService.class));
        } catch (Exception ignored) {}

        Intent intent = new Intent(ctx, EMBReaderActivity.class);
        intent.putExtra(EXTRA_PATH,   path);
        intent.putExtra(EXTRA_PAGE,   page);
        intent.putExtra(EXTRA_PARAG,  parag);
        intent.putExtra(EXTRA_WIDTH,  width);
        intent.putExtra(EXTRA_HEIGHT, height);
        intent.putExtra(EXTRA_FONT,   font);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emb_reader);

        webView      = findViewById(R.id.emb_webview);
        btnSettings  = findViewById(R.id.emb_btn_settings);
        btnPrev      = findViewById(R.id.emb_btn_prev);
        btnPlayPause = findViewById(R.id.emb_btn_play_pause);
        btnNext      = findViewById(R.id.emb_btn_next);
        btnClose     = findViewById(R.id.emb_btn_close);
        tvProgress   = findViewById(R.id.emb_tv_progress);

        applyTint();
        setupWebView();
        setupControls();
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
            updatePlayIcon();
        }
    }

    @Override
    protected void onDestroy() {
        isDestroyed = true;
        isActive    = false;
        if (synthesisQueue != null) synthesisQueue.stop();
        if (webView != null) {
            // Must remove from parent before destroy() to avoid the
            // "WebView.destroy() called while WebView is still attached" warning
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // Tinting
    // -------------------------------------------------------------------------

    private void applyTint() {
        int tint = MagicHelper.getTintColor(), alpha = 230;
        TintUtil.setTintImageWithAlpha(btnSettings,  tint, alpha);
        TintUtil.setTintImageWithAlpha(btnPrev,      tint, alpha);
        TintUtil.setTintImageWithAlpha(btnPlayPause, tint, alpha);
        TintUtil.setTintImageWithAlpha(btnNext,      tint, alpha);
        TintUtil.setTintImageWithAlpha(btnClose,     tint, alpha);
    }

    // -------------------------------------------------------------------------
    // WebView
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
                if (isDestroyed || pageLoaded) return;
                pageLoaded = true;

                // Inject tap-to-seek click listener
                webView.evaluateJavascript(
                    "(function(){" +
                    "document.addEventListener('click',function(e){" +
                    "var i=getSentenceAt(e.clientX,e.clientY);" +
                    "if(i>=0){EMB.onTapped(i);}" +
                    "});" +
                    "})();", null);

                // Show the resume position highlight — but don't start playing
                int from = getIntent().getIntExtra(EXTRA_PARAG, 0);
                if (chapter != null && !chapter.sentences.isEmpty()) {
                    from = Math.max(0, Math.min(from, chapter.sentences.size() - 1));
                }
                currentSentenceIdx = from;
                updateProgress(from);
                final int f = from;
                mainHandler.postDelayed(() -> {
                    if (!isDestroyed) {
                        webView.evaluateJavascript("highlightSentence(" + f + ")", null);
                    }
                }, 300);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Controls
    // -------------------------------------------------------------------------

    private void setupControls() {
        btnPrev.setOnClickListener(v ->
            seekTo(Math.max(0, currentSentenceIdx - 1)));

        btnNext.setOnClickListener(v -> {
            if (chapter == null) return;
            seekTo(Math.min(chapter.sentences.size() - 1, currentSentenceIdx + 1));
        });

        btnPlayPause.setOnClickListener(v -> {
            if (isPlaying) pausePlayback();
            else           startOrResumePlayback();
        });

        btnClose.setOnClickListener(v -> finish());

        btnSettings.setOnClickListener(v -> openSettings());
    }

    // -------------------------------------------------------------------------
    // Settings popup — uses the existing DragingDialogs TTS dialog
    // -------------------------------------------------------------------------

    private void openSettings() {
        DocumentController dc = lastDC;
        if (dc != null) {
            // Use the window's decor view as the popup anchor.
            // DecorView is always a FrameLayout and is always attached to the window,
            // which is what DragingDialogs/DragingPopup requires.
            FrameLayout anchor = (FrameLayout) getWindow().getDecorView();
            DragingDialogs.dialogTextToSpeech(anchor, dc, "");
        } else {
            // No dc available (should not happen with the onResume redirect approach,
            // but fallback just in case).
            Toast.makeText(this, "Open a book first to access TTS settings", Toast.LENGTH_SHORT).show();
        }
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
                postError("Error loading chapter: " + e.getMessage());
            } finally {
                if (dc != null) try { dc.recycle(); } catch (Exception ignored) {}
            }
        }, "@EMB ChapterLoad").start();
    }

    private EMBChapterExtractor.ThemeParams buildTheme() {
        try {
            int bg   = MagicHelper.getBgColor();
            int text = MagicHelper.getTextColor();
            boolean isDark = !AppState.get().isDayNotInvert;
            int highlight  = isDark
                ? android.graphics.Color.parseColor("#5c4a00")
                : android.graphics.Color.parseColor("#ffe082");
            float fontSize = BookCSS.get().fontSizeSp;
            String font    = buildFontFaceCSS(BookCSS.get().normalFont);
            return new EMBChapterExtractor.ThemeParams(bg, text, highlight, fontSize, font);
        } catch (Exception e) {
            LOG.e(e);
            return EMBChapterExtractor.ThemeParams.light();
        }
    }

    private String buildFontFaceCSS(String f) {
        if (f == null || f.isEmpty() || f.contains("/") || f.contains(".ttf") || f.contains(".otf"))
            return "Georgia, 'Noto Serif', serif";
        return "'" + f + "', Georgia, serif";
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
    // Playback
    // -------------------------------------------------------------------------

    private void startOrResumePlayback() {
        if (synthesisQueue != null && isPlaying) return;

        if (chapter == null || chapter.sentences.isEmpty()) {
            Toast.makeText(this, "Chapter not loaded yet", Toast.LENGTH_SHORT).show();
            return;
        }

        if (synthesisQueue == null) {
            // First play — create queue
            synthesisQueue = new SynthesisQueue(new SynthesisQueue.Callback() {
                @Override public void onSentenceStart(int idx, String text) {
                    currentSentenceIdx = idx;
                    isPlaying = true;
                    mainHandler.post(() -> {
                        if (isDestroyed) return;
                        webView.evaluateJavascript("highlightSentence(" + idx + ")", null);
                        updateProgress(idx);
                        updatePlayIcon();
                    });
                }
                @Override public void onFinished() {
                    isPlaying = false;
                    mainHandler.post(() -> { if (!isDestroyed) updatePlayIcon(); });
                }
                @Override public void onError(int idx, Exception e) {
                    LOG.e(e, TAG, "error at sentence", idx);
                }
            });
            isPlaying = true;
            updatePlayIcon();
            synthesisQueue.start(chapter.sentences, currentSentenceIdx);
        } else {
            // Resume from pause
            synthesisQueue.resume();
            isPlaying = true;
            updatePlayIcon();
        }
    }

    private void pausePlayback() {
        if (synthesisQueue != null) synthesisQueue.pause();
        isPlaying = false;
        updatePlayIcon();
    }

    private void seekTo(int idx) {
        if (chapter == null || idx < 0 || idx >= chapter.sentences.size()) return;
        currentSentenceIdx = idx;
        webView.evaluateJavascript("highlightSentence(" + idx + ")", null);
        updateProgress(idx);

        if (synthesisQueue != null) {
            synthesisQueue.seekTo(idx);
            if (isPlaying) {
                // seekTo stops internally; restart
                synthesisQueue = null;
                startOrResumePlayback();
            }
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void updateProgress(int idx) {
        if (chapter == null) return;
        tvProgress.setText((idx + 1) + " / " + chapter.sentences.size());
    }

    private void updatePlayIcon() {
        btnPlayPause.setImageResource(
            isPlaying ? R.drawable.glyphicons_174_pause
                      : R.drawable.glyphicons_175_play);
        TintUtil.setTintImageWithAlpha(btnPlayPause, MagicHelper.getTintColor(), 230);
    }

    // -------------------------------------------------------------------------
    // JS bridge — tap to seek
    // -------------------------------------------------------------------------

    private class JsBridge {
        @JavascriptInterface
        public void onTapped(final int sentIdx) {
            mainHandler.post(() -> { if (!isDestroyed) seekTo(sentIdx); });
        }
    }
}
