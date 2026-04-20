package com.foobnix.pdf.search.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.sys.ImageExtractor;
import com.foobnix.tts.EMBChapterExtractor;
import com.foobnix.tts.SynthesisQueue;
import com.foobnix.tts.TTSEngine;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.core.codec.OutlineLink;

import java.util.ArrayList;
import java.util.List;

/**
 * EMBReaderActivity — chapter-as-article TTS reading view.
 *
 * WHAT IT DOES
 * ------------
 * Presents the current chapter as a continuous, scrollable article in a WebView
 * (no page tiles, no page breaks). Each sentence is wrapped in a <span id="sent-N">
 * element. As TTS plays each sentence, the WebView highlights it via
 * evaluateJavascript("highlightSentence(N)") and smooth-scrolls it into view.
 *
 * LAUNCH
 * ------
 * From DocumentController context (e.g. a button in DocumentWrapperUI or the
 * TTSControlsView):
 *
 *   EMBReaderActivity.launch(dc);
 *
 * The static launch() method extracts path, page, width, height, font from dc.
 *
 * TTS PLAYBACK
 * ------------
 * SynthesisQueue pre-synthesises sentences to WAV files and plays them gaplessly
 * via MediaPlayer.setNextMediaPlayer(). The queue calls back onSentenceStart(idx)
 * on the main thread for each sentence — this is where the WebView highlight fires.
 *
 * CONTROLS
 * --------
 *   ← Prev sentence : stop + restart from currentSentenceIdx - 1
 *   ▶/‖ Play/Pause  : SynthesisQueue.pause() / resume()
 *   → Next sentence : stop + restart from currentSentenceIdx + 1
 *   ✕ Close          : finish() — returns to the main reader
 *   Tap on sentence  : JS getSentenceAt(x, y) → seek to that sentence
 *
 * POSITION PERSISTENCE
 * --------------------
 * AppSP.lastBookParagraph is written on every onSentenceStart callback.
 * On next launch the saved paragraph is used as fromIndex.
 *
 * OUTLINE / CHAPTER DETECTION
 * ----------------------------
 * Automatic: EMBChapterExtractor.extract() uses dc.getOutline() to find the
 * chapter boundaries containing the current page.
 * Manual: not yet implemented — the UI could present a spinner with chapter titles
 * and call EMBChapterExtractor.extractRange() with the selected start/end pages.
 *
 * MANIFEST
 * --------
 * Add to AndroidManifest.xml inside <application>:
 *
 *   <activity
 *       android:name=".pdf.search.activity.EMBReaderActivity"
 *       android:configChanges="orientation|screenSize|keyboardHidden"
 *       android:windowSoftInputMode="adjustNothing"
 *       android:exported="false" />
 */
@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
public class EMBReaderActivity extends Activity {

    // -------------------------------------------------------------------------
    // Intent extras
    // -------------------------------------------------------------------------

    public static final String EXTRA_PATH   = "emb_path";
    public static final String EXTRA_PAGE   = "emb_page";    // 0-indexed
    public static final String EXTRA_PARAG  = "emb_parag";   // sentence index to start from
    public static final String EXTRA_WIDTH  = "emb_width";
    public static final String EXTRA_HEIGHT = "emb_height";
    public static final String EXTRA_FONT   = "emb_font";    // sp

    private static final String TAG = "EMBReaderActivity";

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private WebView webView;
    private ImageView btnPrev;
    private ImageView btnPlayPause;
    private ImageView btnNext;
    private ImageView btnClose;
    private TextView  tvProgress;

    private EMBChapterExtractor.ChapterContent chapter;
    private SynthesisQueue synthesisQueue;

    private volatile int currentSentenceIdx = 0;
    private volatile boolean isPlaying = false;
    private volatile boolean isDestroyed = false; // guards background thread post()

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Launch helpers
    // -------------------------------------------------------------------------

    /**
     * Launch from a DocumentController — extracts all required parameters automatically.
     */
    public static void launch(DocumentController dc) {
        Context ctx = dc.getActivity();
        if (ctx == null) return;

        int page  = dc.getCurentPageFirst1() - 1; // convert to 0-indexed
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
     * Launch with explicit parameters (e.g. from the notification or TTSService).
     */
    public static void launch(Context ctx, String path, int page, int parag,
                              int width, int height, int font) {
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
    // Activity lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emb_reader);

        webView         = findViewById(R.id.emb_webview);
        btnPrev         = findViewById(R.id.emb_btn_prev);
        btnPlayPause    = findViewById(R.id.emb_btn_play_pause);
        btnNext         = findViewById(R.id.emb_btn_next);
        btnClose        = findViewById(R.id.emb_btn_close);
        tvProgress      = findViewById(R.id.emb_tv_progress);

        setupWebView();
        setupControls();
        loadChapterAsync();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Pause synthesis/playback when activity goes to background.
        // User can resume by tapping the play button on return.
        if (synthesisQueue != null && isPlaying) {
            synthesisQueue.pause();
            isPlaying = false;
            updatePlayPauseIcon();
        }
    }

    @Override
    protected void onDestroy() {
        isDestroyed = true;
        if (synthesisQueue != null) synthesisQueue.stop();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // WebView setup
    // -------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(false);       // no localStorage needed
        ws.setBuiltInZoomControls(false);
        ws.setSupportZoom(false);
        ws.setTextZoom(100);
        ws.setLoadsImagesAutomatically(false); // article view is text-only

        // JS → Java bridge for tap-to-seek
        webView.addJavascriptInterface(new JsBridge(), "EMB");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // After the HTML has loaded, inject a click listener that calls
                // EMB.onSentenceTapped(idx) when the user taps a sentence span.
                webView.evaluateJavascript(
                    "(function() {" +
                    "  document.addEventListener('click', function(e) {" +
                    "    var idx = getSentenceAt(e.clientX, e.clientY);" +
                    "    if (idx >= 0) { EMB.onSentenceTapped(idx); }" +
                    "  });" +
                    "})();",
                    null
                );

                // Start playback from the saved sentence position
                int fromSentence = getIntent().getIntExtra(EXTRA_PARAG, 0);
                // Clamp to valid range (the saved parag index may exceed the
                // sentence count if the user navigated to a different chapter)
                if (chapter != null) {
                    fromSentence = Math.min(fromSentence, chapter.sentences.size() - 1);
                    fromSentence = Math.max(fromSentence, 0);
                }

                // Brief delay so WebView finishes rendering before we scroll
                final int startIdx = fromSentence;
                mainHandler.postDelayed(() -> {
                    if (!isDestroyed) {
                        startPlayback(startIdx);
                    }
                }, 400);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Control bar
    // -------------------------------------------------------------------------

    private void setupControls() {
        btnPrev.setOnClickListener(v -> {
            int target = Math.max(0, currentSentenceIdx - 1);
            seekToSentence(target);
        });

        btnPlayPause.setOnClickListener(v -> {
            if (isPlaying) {
                pausePlayback();
            } else {
                resumePlayback();
            }
        });

        btnNext.setOnClickListener(v -> {
            if (chapter == null) return;
            int target = Math.min(chapter.sentences.size() - 1, currentSentenceIdx + 1);
            seekToSentence(target);
        });

        btnClose.setOnClickListener(v -> finish());
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
            Toast.makeText(this, "No book path supplied", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        new Thread(() -> {
            CodecDocument dc = null;
            try {
                dc = ImageExtractor.singleCodecContext(path, "");
                if (dc == null) {
                    postError("Cannot open book file");
                    return;
                }
                dc.getPageCount(width, height, font);

                List<OutlineLinkWrapper> outline = convertOutline(dc.getOutline());

                final EMBChapterExtractor.ChapterContent result =
                    EMBChapterExtractor.extract(dc, page, outline);

                if (isDestroyed) return;

                chapter = result;
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    updateProgress(0);
                    // loadDataWithBaseURL with a file:// base so relative assets resolve
                    webView.loadDataWithBaseURL(
                        "file:///android_asset/",
                        result.html,
                        "text/html",
                        "utf-8",
                        null
                    );
                });

            } catch (Exception e) {
                LOG.e(e);
                postError("Error loading chapter: " + e.getMessage());
            } finally {
                if (dc != null) {
                    try { dc.recycle(); } catch (Exception ignored) {}
                }
            }
        }, "@EMB ChapterLoad").start();
    }

    /**
     * Convert the codec's raw OutlineLink list to OutlineLinkWrapper list
     * so EMBChapterExtractor can read targetPage values.
     */
    private List<OutlineLinkWrapper> convertOutline(List<? extends OutlineLink> raw) {
        List<OutlineLinkWrapper> result = new ArrayList<>();
        if (raw == null) return result;
        for (OutlineLink link : raw) {
            try {
                OutlineLinkWrapper w = new OutlineLinkWrapper(
                    link.getTitle(),
                    link.getLink(),
                    link.getLevel(),
                    link.linkUri
                );
                if (w.targetPage > 0) { // only entries that resolve to a page number
                    result.add(w);
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    private void postError(String msg) {
        mainHandler.post(() -> {
            if (!isDestroyed) {
                Toast.makeText(EMBReaderActivity.this, msg, Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Playback control
    // -------------------------------------------------------------------------

    private void startPlayback(int fromSentence) {
        if (chapter == null || chapter.sentences.isEmpty()) return;
        fromSentence = Math.max(0, Math.min(fromSentence, chapter.sentences.size() - 1));

        // Stop any previous queue before creating a new one
        if (synthesisQueue != null) synthesisQueue.stop();

        synthesisQueue = new SynthesisQueue(this, new SynthesisQueue.Callback() {

            @Override
            public void onSentenceStart(int idx, String text) {
                currentSentenceIdx = idx;
                isPlaying = true;
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    // Highlight in WebView
                    webView.evaluateJavascript("highlightSentence(" + idx + ")", null);
                    updateProgress(idx);
                    updatePlayPauseIcon();
                    // Persist position so the main reader and notification can resume here
                    AppSP.get().lastBookParagraph = idx;
                    AppSP.get().save();
                });
            }

            @Override
            public void onFinished() {
                isPlaying = false;
                currentSentenceIdx = chapter != null ? chapter.sentences.size() - 1 : 0;
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    updatePlayPauseIcon();
                    // TODO: optionally auto-advance to the next chapter
                });
            }

            @Override
            public void onError(int idx, Exception e) {
                LOG.e(TAG, "SynthesisQueue error at sentence", idx, e);
                // Non-fatal: queue internally skips the failed sentence
            }
        });

        currentSentenceIdx = fromSentence;
        isPlaying = true;
        updatePlayPauseIcon();
        synthesisQueue.start(chapter.sentences, fromSentence);

        // Pre-highlight immediately so the user sees something before audio starts
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
            // First play after load, or after a seek that cleared the queue
            startPlayback(currentSentenceIdx);
        }
    }

    /**
     * Seek to a specific sentence index — stops the current queue and restarts.
     */
    private void seekToSentence(int idx) {
        if (chapter == null || idx < 0 || idx >= chapter.sentences.size()) return;
        currentSentenceIdx = idx;
        // Highlight immediately (before synthesis begins) for instant feedback
        webView.evaluateJavascript("highlightSentence(" + idx + ")", null);
        startPlayback(idx);
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void updateProgress(int idx) {
        if (chapter == null) return;
        int total = chapter.sentences.size();
        tvProgress.setText((idx + 1) + " / " + total);
    }

    private void updatePlayPauseIcon() {
        if (isPlaying) {
            btnPlayPause.setImageResource(R.drawable.glyphicons_174_pause);
        } else {
            btnPlayPause.setImageResource(R.drawable.glyphicons_175_play);
        }
    }

    // -------------------------------------------------------------------------
    // JS ↔ Java bridge (tap-to-seek)
    // -------------------------------------------------------------------------

    /**
     * Called from the injected JS click listener when the user taps a sentence span.
     * Runs on the WebView's JS thread — must post to main thread for any UI work.
     */
    private class JsBridge {
        @JavascriptInterface
        public void onSentenceTapped(final int sentIdx) {
            LOG.d(TAG, "tap → sentence", sentIdx);
            mainHandler.post(() -> {
                if (!isDestroyed) seekToSentence(sentIdx);
            });
        }
    }
}
