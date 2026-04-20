package com.foobnix.tts;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppState;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SynthesisQueue — gapless TTS playback via pre-synthesis + MediaPlayer chaining.
 *
 * PROBLEM WITH DIRECT TTS SPEAK
 * --------------------------------
 * Android TTS QUEUE_ADD inserts a silence between utterances while the engine
 * re-initialises its audio track. With default settings this is ~200–400ms of
 * dead air between every sentence. Voice Aloud Reader eliminates this by:
 *   1. Synthesising each sentence to a WAV file (no audio output).
 *   2. Playing the WAVs via MediaPlayer.setNextMediaPlayer() — the OS schedules
 *      the next player's start to exactly where the current one ends, zero gap.
 *
 * PIPELINE
 * --------
 *
 *   Synthesis thread (single-threaded executor):
 *     synthesise sentences[synthIdx] → cacheDir/tts_wav/sent-{synthIdx}.wav
 *     when WAV is ready (UtteranceProgressListener.onDone) → onWavReady(idx)
 *     advance synthIdx, synthesise next (up to PREFETCH_AHEAD ahead of playIdx)
 *
 *   Main thread (via mainHandler):
 *     onWavReady(idx):
 *       if no currentPlayer exists → create, prepare, start → fire onSentenceStart(idx)
 *       if currentPlayer exists     → create nextPlayer, call setNextMediaPlayer()
 *     currentPlayer.OnCompletion:
 *       promote nextPlayer to currentPlayer (it is already playing due to gapless chain)
 *       fire onSentenceStart(playIdx)
 *       delete old WAV
 *       synthesise further ahead
 *
 * RACE CONDITION (WAV ready vs playback end)
 * ------------------------------------------
 *   Case A — WAV[n+1] ready BEFORE player[n] ends (normal, prefetch works):
 *     → setNextMediaPlayer() is called → gapless
 *   Case B — player[n] ends BEFORE WAV[n+1] is ready (slow synthesis engine):
 *     → pendingStart flag is set; when WAV[n+1] eventually arrives, we
 *       start player[n+1] manually (tiny gap, unavoidable)
 *
 * SEEK
 * ----
 *   stop() clears all state. start(sentences, newIdx) restarts from scratch.
 *   EMBReaderActivity calls stop() then start() on tap-to-seek.
 *
 * PAUSE / RESUME
 * --------------
 *   pause() calls currentPlayer.pause() and sets paused flag.
 *   resume() calls currentPlayer.start() — no re-synthesis needed.
 *
 * WAV FILE MANAGEMENT
 * -------------------
 *   Files live in getCacheDir()/tts_wav/sent-{N}.wav.
 *   Each file is deleted in its own OnCompletion callback.
 *   The directory is purged on stop() to clean up any orphans.
 */
public class SynthesisQueue {

    private static final String TAG = "SynthesisQueue";

    /**
     * How many sentences ahead of the current playback position to synthesise.
     * 2 is enough to guarantee gapless on most devices. Increase if your TTS
     * engine is slow (e.g. neural voices).
     */
    private static final int PREFETCH_AHEAD = 2;

    // -------------------------------------------------------------------------
    // Callback
    // -------------------------------------------------------------------------

    public interface Callback {
        /** Fired on the main thread when sentence at index starts being spoken. */
        void onSentenceStart(int index, String text);
        /** Fired on the main thread after the last sentence completes. */
        void onFinished();
        /** Fired on the main thread if synthesis or playback fails for a sentence. */
        void onError(int index, Exception e);
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Context context;
    private final Callback callback;
    private final File wavDir;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Single-threaded: synthesis calls are serialised — one at a time. */
    private final ExecutorService synthExecutor = Executors.newSingleThreadExecutor();

    private List<String> sentences;

    /** Index of the sentence currently playing (or about to start). */
    private volatile int playIdx;

    /** Index of the sentence currently being synthesised. */
    private volatile int synthIdx;

    /** True when a synthesiseNext() call is in flight on synthExecutor. */
    private final AtomicBoolean synthBusy = new AtomicBoolean(false);

    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final AtomicBoolean paused  = new AtomicBoolean(false);

    // -------------------------------------------------------------------------
    // MediaPlayer chain
    // -------------------------------------------------------------------------

    /** The MediaPlayer currently producing audio output. May be null. */
    private MediaPlayer currentPlayer;

    /** The MediaPlayer prepared and set as next via setNextMediaPlayer(). May be null. */
    private MediaPlayer pendingNextPlayer;

    /**
     * Set when currentPlayer's OnCompletion fires but WAV[playIdx] is not yet ready.
     * onWavReady will check this and start the player immediately.
     */
    private boolean waitingForNextWav = false;

    /** Guards all MediaPlayer field access — always acquired on the main thread. */
    private final Object playerLock = new Object();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public SynthesisQueue(Context context, Callback callback) {
        this.context  = context.getApplicationContext();
        this.callback = callback;
        this.wavDir   = new File(context.getCacheDir(), "tts_wav");
        if (!wavDir.exists()) wavDir.mkdirs();
        purgeWavDir(); // clean up any orphans from a previous crash
    }

    // -------------------------------------------------------------------------
    // Public control API
    // -------------------------------------------------------------------------

    /**
     * Start gapless playback from sentences.get(fromIndex).
     * Any active playback is stopped first.
     */
    public synchronized void start(List<String> sentences, int fromIndex) {
        stop();

        this.sentences = sentences;
        this.playIdx   = fromIndex;
        this.synthIdx  = fromIndex;
        stopped.set(false);
        paused.set(false);
        waitingForNextWav = false;

        LOG.d(TAG, "start: fromIndex=", fromIndex, "total=", sentences.size());
        synthesiseNext();
    }

    /**
     * Pause playback. Current sentence position is preserved; resume() continues
     * from the same sentence without re-synthesis.
     */
    public synchronized void pause() {
        if (stopped.get() || paused.get()) return;
        paused.set(true);
        synchronized (playerLock) {
            if (currentPlayer != null) {
                try { currentPlayer.pause(); } catch (Exception e) { LOG.e(e); }
            }
        }
        LOG.d(TAG, "paused at sentence", playIdx);
    }

    /**
     * Resume after pause(). Continues the current sentence from where it left off.
     */
    public synchronized void resume() {
        if (stopped.get() || !paused.get()) return;
        paused.set(false);
        synchronized (playerLock) {
            if (currentPlayer != null) {
                try {
                    currentPlayer.start();
                    callback.onSentenceStart(playIdx, sentenceText(playIdx));
                } catch (Exception e) { LOG.e(e); }
            } else {
                // No player — re-synthesise from current position
                synthesiseNext();
            }
        }
        LOG.d(TAG, "resumed at sentence", playIdx);
    }

    /**
     * Stop all playback and synthesis. Releases MediaPlayers and purges WAV cache.
     * Safe to call from any thread.
     */
    public synchronized void stop() {
        stopped.set(true);
        paused.set(false);

        // Tell the TTS engine to abandon whatever it's synthesising
        try {
            TextToSpeech tts = TTSEngine.get().getTTS();
            if (tts != null) tts.stop();
        } catch (Exception e) { LOG.e(e); }

        // Release players on the main thread (they must be released on the same
        // thread they were created on, and we always create them on the main thread)
        mainHandler.post(() -> {
            synchronized (playerLock) {
                releasePlayer(currentPlayer);
                releasePlayer(pendingNextPlayer);
                currentPlayer     = null;
                pendingNextPlayer = null;
                waitingForNextWav = false;
            }
        });

        purgeWavDir();
        LOG.d(TAG, "stopped");
    }

    // -------------------------------------------------------------------------
    // Synthesis pipeline
    // -------------------------------------------------------------------------

    /**
     * Submit the next pending sentence for synthesis, if prefetch budget allows.
     * Must only be called when synthBusy is false.
     */
    private void synthesiseNext() {
        if (stopped.get()) return;
        if (sentences == null || synthIdx >= sentences.size()) return;

        // Don't get too far ahead — avoid piling up WAV files in cache
        if (synthIdx - playIdx > PREFETCH_AHEAD) {
            LOG.d(TAG, "prefetch limit: synthIdx=", synthIdx, "playIdx=", playIdx);
            return;
        }

        if (!synthBusy.compareAndSet(false, true)) {
            LOG.d(TAG, "synthesiseNext: already busy");
            return;
        }

        final int idx  = synthIdx;
        final String text = sentences.get(idx);
        final File   wav  = wavFile(idx);

        LOG.d(TAG, "synthesising sentence", idx, "len=", text.length());

        synthExecutor.execute(() -> doSynthesise(idx, text, wav));
    }

    /** Called on synthExecutor thread. Calls synthesizeToFile and registers the callback. */
    private void doSynthesise(final int idx, final String text, final File wav) {
        try {
            TextToSpeech tts = TTSEngine.get().getTTS();
            if (tts == null) {
                synthBusy.set(false);
                mainHandler.post(() -> callback.onError(idx,
                    new IllegalStateException("TTS engine not initialised")));
                return;
            }

            // Apply the same pitch/speed settings as the regular TTS path
            tts.setPitch(AppState.get().ttsPitch);
            tts.setSpeechRate(AppState.get().ttsSpeed);

            final String uid = "SQ_" + idx;

            // We set a fresh UtteranceProgressListener before each synthesizeToFile call.
            // This is safe because synthesis is serialised on a single thread and
            // EMBReaderActivity stops TTSService before starting SynthesisQueue.
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { /* synthesis started */ }

                @Override
                public void onDone(String utteranceId) {
                    if (!uid.equals(utteranceId)) return;
                    LOG.d(TAG, "synthesis done: sent=", idx, "bytes=",
                          wav.exists() ? wav.length() : 0L);
                    synthBusy.set(false);
                    synthIdx++;
                    // Hand off to the main thread for MediaPlayer setup
                    mainHandler.post(() -> onWavReady(idx, wav));
                }

                @Override
                public void onError(String utteranceId) {
                    if (!uid.equals(utteranceId)) return;
                    LOG.d(TAG, "synthesis error for sentence", idx);
                    synthBusy.set(false);
                    synthIdx++;
                    // Still attempt playback — wav may be partial but playable
                    mainHandler.post(() -> onWavReady(idx, wav));
                }
            });

            // Use the deprecated HashMap API to stay consistent with the rest of the
            // codebase (TTSEngine.speakToFile uses the same signature).
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid);

            int result = tts.synthesizeToFile(text, params, wav.getAbsolutePath());
            if (result != TextToSpeech.SUCCESS) {
                LOG.d(TAG, "synthesizeToFile returned", result, "for sentence", idx);
                synthBusy.set(false);
                mainHandler.post(() -> callback.onError(idx,
                    new RuntimeException("synthesizeToFile failed, result=" + result)));
            }

        } catch (Exception e) {
            LOG.e(e);
            synthBusy.set(false);
            mainHandler.post(() -> callback.onError(idx, e));
        }
    }

    // -------------------------------------------------------------------------
    // MediaPlayer management (main thread only)
    // -------------------------------------------------------------------------

    /**
     * Called on the main thread when wav for sentence idx is ready on disk.
     * Either starts the first player or chains it as the next player.
     */
    private void onWavReady(int idx, File wav) {
        if (stopped.get()) return;

        // Skip empty WAVs (synthesis produced nothing — blank/whitespace sentence)
        if (!wav.exists() || wav.length() < 44) { // 44 bytes = WAV header only
            LOG.d(TAG, "wav empty for sentence", idx, "— skipping");
            advanceAfterEmpty(idx);
            synthesiseNext();
            return;
        }

        MediaPlayer mp = buildPlayer(idx, wav);
        if (mp == null) {
            // buildPlayer failed — skip this sentence
            advanceAfterEmpty(idx);
            synthesiseNext();
            return;
        }

        synchronized (playerLock) {
            if (stopped.get()) {
                releasePlayer(mp);
                return;
            }

            if (currentPlayer == null) {
                // ---- First sentence (or restart after gap) ----
                currentPlayer = mp;
                if (!paused.get()) {
                    mp.start();
                    callback.onSentenceStart(idx, sentenceText(idx));
                }
                LOG.d(TAG, "started player for sentence", idx);

            } else if (waitingForNextWav) {
                // ---- Main thread was waiting for this WAV (Case B race) ----
                // currentPlayer already ended; start mp immediately as the new current
                waitingForNextWav = false;
                currentPlayer = mp;
                if (!paused.get()) {
                    mp.start();
                    callback.onSentenceStart(idx, sentenceText(idx));
                }
                LOG.d(TAG, "started late player for sentence", idx, "(gap unavoidable)");

            } else {
                // ---- Chain as next player (Case A — gapless) ----
                pendingNextPlayer = mp;
                try {
                    currentPlayer.setNextMediaPlayer(mp);
                    LOG.d(TAG, "chained sentence", idx, "as next player (gapless)");
                } catch (Exception e) {
                    // setNextMediaPlayer can throw if the current player is nearly done.
                    // Fall back: the OnCompletion will start mp via waitingForNextWav path.
                    LOG.d(TAG, "setNextMediaPlayer failed for sent", idx, "—", e.getMessage());
                }
            }
        }

        // Synthesise further ahead now that this slot is filled
        synthesiseNext();
    }

    /**
     * Create and prepare a MediaPlayer for the given WAV file.
     * Returns null on failure (caller should skip this sentence).
     */
    private MediaPlayer buildPlayer(final int idx, final File wav) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(wav.getAbsolutePath());
            mp.prepare(); // synchronous — fine for local files, typically <5ms

            mp.setOnCompletionListener(completedMp -> {
                // This fires on the main thread when completedMp has finished.
                // If setNextMediaPlayer was called, the next player has already started.
                onPlayerComplete(idx, wav);
            });

            return mp;
        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    /**
     * Called on the main thread when sentence idx finishes playing.
     */
    private void onPlayerComplete(int idx, File wav) {
        if (stopped.get()) return;

        // Delete the WAV we just finished with
        wav.delete();

        int nextIdx = idx + 1;
        playIdx = nextIdx;

        if (sentences == null || nextIdx >= sentences.size()) {
            // Reached end of sentence list
            synchronized (playerLock) {
                releasePlayer(currentPlayer);
                currentPlayer = null;
            }
            LOG.d(TAG, "all sentences finished");
            callback.onFinished();
            return;
        }

        synchronized (playerLock) {
            // Promote pendingNextPlayer to current
            releasePlayer(currentPlayer);
            currentPlayer     = pendingNextPlayer;
            pendingNextPlayer = null;

            if (currentPlayer != null) {
                // Gapless path: next player is already running (set via setNextMediaPlayer)
                callback.onSentenceStart(nextIdx, sentenceText(nextIdx));
                LOG.d(TAG, "gapless advance to sentence", nextIdx);
            } else {
                // Gap path: WAV[nextIdx] isn't ready yet — flag it
                waitingForNextWav = true;
                LOG.d(TAG, "waiting for wav of sentence", nextIdx);
            }
        }

        // Synthesise the one after next
        synthesiseNext();
    }

    /** Advance playback index without a player (blank sentence skipped). */
    private void advanceAfterEmpty(int idx) {
        playIdx = idx + 1;
        if (sentences != null && playIdx < sentences.size()) {
            callback.onSentenceStart(playIdx, sentenceText(playIdx));
        } else {
            callback.onFinished();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private File wavFile(int idx) {
        return new File(wavDir, "sent-" + idx + ".wav");
    }

    private String sentenceText(int idx) {
        if (sentences == null || idx < 0 || idx >= sentences.size()) return "";
        return sentences.get(idx);
    }

    private void releasePlayer(MediaPlayer mp) {
        if (mp == null) return;
        try {
            if (mp.isPlaying()) mp.stop();
            mp.reset();
            mp.release();
        } catch (Exception e) { LOG.e(e); }
    }

    private void purgeWavDir() {
        try {
            File[] files = wavDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        } catch (Exception ignored) {}
    }
}
