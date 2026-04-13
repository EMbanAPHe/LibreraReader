package com.foobnix.tts;

/**
 * EventBus event posted by TTSService when a new utterance range starts being spoken.
 *
 * paragIndex  — index into the TTS_PAUSE-split parts array for the current page.
 *               Matches the number embedded in the utteranceId (FINISHED_SIGNAL + i).
 * start / end — character offsets within the paragraph string (from onRangeStart, API 26+).
 *               Both are -1 on older APIs where only paragraph-level granularity is available.
 * text        — the full paragraph string being spoken (the parts[paragIndex] value).
 *               Used to display the current sentence in the persistent TTS bar.
 *
 * TODO (canvas highlighting): to draw a highlight rectangle on the page, map paragIndex
 * back to the original page text, then use MuPDF StructuredText.getBlocks() / TextWord
 * bounding boxes to find the screen coordinates of the matching text range.
 * That mapping lives in DecodeServiceBase / PdfSurfaceView and is a follow-up task.
 */
public class TtsHighlightEvent {

    public final int paragIndex;
    public final int start;
    public final int end;
    public final String text;

    public TtsHighlightEvent(int paragIndex, int start, int end, String text) {
        this.paragIndex = paragIndex;
        this.start = start;
        this.end = end;
        this.text = text;
    }

    /** Convenience constructor for paragraph-start events (no character range). */
    public TtsHighlightEvent(int paragIndex, String text) {
        this(paragIndex, -1, -1, text);
    }
}
