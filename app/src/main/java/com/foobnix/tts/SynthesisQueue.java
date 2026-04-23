package com.foobnix.tts;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppState;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SynthesisQueue — sentence-level TTS playback with pre-queuing.
 *
 * WHY QUEUE_ADD INSTEAD OF WAV FILES
 * -----------------------------------
 * The previous WAV-file approach had three fatal race conditions:
 *   1. synthesizeToFile() sets OnUtteranceProgressListener per call, overwriting previous
 *   2. MediaPlayer.setNextMediaPlayer() is timing-sensitive and frequently missed
 *   3. Two audio streams (TTSService + MediaPlayer) could play simultaneously
 *
 * This version uses speak(QUEUE_ADD) exclusively. The Android TTS API serialises
 * utterances internally — sentence N+1 begins exactly when N ends, with whatever
 * gap the engine produces. For sherpa-onnx (the target engine), which pre-synthesises
 * on speak() calls, the gap is near-zero because the audio is already ready.
 *
 * PRE-QUEUING FOR SHERPA-ONNX
 * ----------------------------
 * sherpa-onnx can pre-synthesise audio as soon as it receives a speak() call.
 * We pre-queue PREFETCH_AHEAD sentences immediately on start(), and on each
 * onDone() we queue one more. This keeps the engine's internal buffer full so
 * audio is always ready when the previous sentence ends — true gapless.
 *
 * ARCHITECTURE
 * ------------
 *   start(sentences, from):
 *     QUEUE_FLUSH to clear any leftover audio
 *     queue sentences[from .. from+PREFETCH_AHEAD] via speak(QUEUE_ADD)
 *
 *   onStart(utteranceId "EMB_N"):
 *     → fire callback.onSentenceStart(N)   ← highlight fires here (audio is starting)
 *
 *   onDone(utteranceId "EMB_N"):
 *     → queue sentences[N + PREFETCH_AHEAD] if not stopped
 *     → if N is the last sentence → fire callback.onFinished()
 *
 *   pause():  tts.stop()  (stops current utterance; next speak() starts fresh)
 *   resume(): re-queue from currentSentenceIdx via start()
 *   stop():   tts.stop() + reset state
 *   seek(N):  stop() + start(sentences, N)
 *
 * UTTERANCE IDS
 * -------------
 *   Each sentence gets id "EMB_N" where N is its index. The listener parses N
 *   from the id. This is robust to onStart/onDone arriving out of order because
 *   each event carries its own index.
 */
public class SynthesisQueue {

    private static final String TAG = "SynthesisQueue";
    private static final String UID_PREFIX = "EMB_";

    /**
     * How many sentences ahead of the current play position to pre-queue.
     * 3 gives sherpa-onnx enough lead time to synthesise without stalling.
     * Reduce to 1 for slower engines to avoid long startup delays.
     */
    private static final int PREFETCH_AHEAD = 3;

    // -------------------------------------------------------------------------
    // Callback
    // -------------------------------------------------------------------------

    public interface Callback {
        /** Called on the main thread when sentence idx starts being spoken. */
        void onSentenceStart(int index, String text);
        /** Called on the main thread when the last sentence finishes. */
        void onFinished();
        /** Called on the main thread on any TTS error. */
        void onError(int index, Exception e);
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<String> sentences;
    private volatile int  currentIdx   = 0;
    private volatile int  queuedUpTo   = -1; // last index we've passed to speak()
    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final AtomicBoolean paused  = new AtomicBoolean(false);

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public SynthesisQueue(Callback callback) {
        this.callback = callback;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public synchronized void start(List<String> sentences, int fromIndex) {
        if (sentences == null || sentences.isEmpty()) return;

        this.sentences   = sentences;
        this.currentIdx  = Math.max(0, Math.min(fromIndex, sentences.size() - 1));
        this.queuedUpTo  = currentIdx - 1;

        stopped.set(false);
        paused.set(false);

        TextToSpeech tts = getTTS();
        if (tts == null) {
            mainHandler.post(() -> callback.onError(-1,
                new IllegalStateException("TTS engine not initialised")));
            return;
        }

        applySettings(tts);
        registerListener(tts);

        // FLUSH clears any leftover audio from TTSService or a previous queue run
        tts.speak("", TextToSpeech.QUEUE_FLUSH, null, UID_PREFIX + "flush");

        // Pre-queue initial batch
        for (int i = currentIdx; i < Math.min(sentences.size(), currentIdx + PREFETCH_AHEAD); i++) {
            queueSentence(tts, i);
        }

        LOG.d(TAG, "started from", currentIdx, "queued up to", queuedUpTo);
    }

    public synchronized void pause() {
        if (stopped.get() || paused.get()) return;
        paused.set(true);
        TextToSpeech tts = getTTS();
        if (tts != null) tts.stop();
        LOG.d(TAG, "paused at", currentIdx);
    }

    public synchronized void resume() {
        if (stopped.get() || !paused.get()) return;
        paused.set(false);
        // Restart from current position — re-queues from currentIdx
        start(sentences, currentIdx);
        LOG.d(TAG, "resumed from", currentIdx);
    }

    public synchronized void seekTo(int idx) {
        if (sentences == null) return;
        idx = Math.max(0, Math.min(idx, sentences.size() - 1));
        boolean wasPlaying = !stopped.get() && !paused.get();
        stop();
        if (wasPlaying) {
            start(sentences, idx);
        } else {
            // Just update position without starting
            currentIdx = idx;
        }
    }

    public synchronized void stop() {
        stopped.set(true);
        paused.set(false);
        TextToSpeech tts = getTTS();
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
        }
        LOG.d(TAG, "stopped");
    }

    public int getCurrentIndex() {
        return currentIdx;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /** Queue a single sentence via QUEUE_ADD. */
    private void queueSentence(TextToSpeech tts, int idx) {
        if (idx < 0 || idx >= sentences.size()) return;
        String text = sentences.get(idx);
        if (text == null || text.trim().isEmpty()) {
            // Skip empty sentence — fire a synthetic onDone so we advance
            mainHandler.post(() -> handleDone(idx));
            return;
        }

        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UID_PREFIX + idx);

        int result = tts.speak(text, TextToSpeech.QUEUE_ADD, params, UID_PREFIX + idx);
        if (result == TextToSpeech.SUCCESS) {
            queuedUpTo = idx;
        } else {
            LOG.d(TAG, "speak() failed for sentence", idx, "result=", result);
            mainHandler.post(() -> callback.onError(idx,
                new RuntimeException("speak() returned " + result)));
        }
    }

    /** Register a single persistent listener on the engine. */
    private void registerListener(TextToSpeech tts) {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {

            @Override
            public void onStart(String utteranceId) {
                if (stopped.get() || paused.get()) return;
                int idx = parseIdx(utteranceId);
                if (idx < 0) return;

                currentIdx = idx;
                final String text = (sentences != null && idx < sentences.size())
                                    ? sentences.get(idx) : "";

                mainHandler.post(() -> {
                    if (!stopped.get()) {
                        callback.onSentenceStart(idx, text);
                        // Persist position
                        try {
                            com.foobnix.model.AppSP.get().lastBookParagraph = idx;
                            com.foobnix.model.AppSP.get().save();
                        } catch (Exception ignored) {}
                    }
                });
            }

            @Override
            public void onDone(String utteranceId) {
                if (utteranceId != null && utteranceId.equals(UID_PREFIX + "flush")) return;
                int idx = parseIdx(utteranceId);
                if (idx < 0) return;
                handleDone(idx);
            }

            @Override
            public void onError(String utteranceId) {
                int idx = parseIdx(utteranceId);
                LOG.d(TAG, "TTS onError for sentence", idx);
                // Advance anyway so we don't get stuck
                if (idx >= 0) handleDone(idx);
            }
        });
    }

    /**
     * Called when sentence idx finishes (onDone or synthetic skip).
     * Queues the next prefetch sentence and fires onFinished at the end.
     */
    private void handleDone(int idx) {
        if (stopped.get() || paused.get()) return;

        // Queue one more sentence to keep the prefetch buffer topped up
        int nextToQueue = queuedUpTo + 1;
        if (sentences != null && nextToQueue < sentences.size()) {
            TextToSpeech tts = getTTS();
            if (tts != null) queueSentence(tts, nextToQueue);
        }

        // If this was the last sentence, notify finished
        if (sentences != null && idx >= sentences.size() - 1) {
            stopped.set(true);
            mainHandler.post(() -> callback.onFinished());
        }
    }

    private void applySettings(TextToSpeech tts) {
        try {
            tts.setSpeechRate(AppState.get().ttsSpeed);
            tts.setPitch(AppState.get().ttsPitch);
        } catch (Exception e) { LOG.e(e); }
    }

    private TextToSpeech getTTS() {
        try { return TTSEngine.get().getTTS(); } catch (Exception e) { return null; }
    }

    private int parseIdx(String utteranceId) {
        if (utteranceId == null || !utteranceId.startsWith(UID_PREFIX)) return -1;
        try {
            return Integer.parseInt(utteranceId.substring(UID_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
