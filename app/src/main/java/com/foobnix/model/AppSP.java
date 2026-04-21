package com.foobnix.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import com.foobnix.android.utils.Objects;
import com.foobnix.pdf.info.Android6;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.Urls;

import java.io.File;

public class AppSP {


    private static AppSP instance = new AppSP();
    public String lastBookPath;

    public int lastBookPage = 0;
    public int lastBookPageCount = 0;
    public int tempBookPage = 0;
    public volatile int lastBookParagraph = 0;
    public String lastBookTitle;
    public int lastBookWidth = 0;
    public int lastBookHeight = 0;
    public int lastFontSize = 0;
    public String lastBookLang = "";
    public boolean isLocked = false;
    public boolean isFirstTimeVertical = true;
    public boolean isFirstTimeHorizontal = true;

    /** When true, opening a book launches EMBReaderActivity instead of the tile-based view. */
    public boolean embModeDefault = false;

    public int readingMode = AppState.READING_MODE_BOOK;
    public long syncTime;
    public int syncTimeStatus;
    public String hypenLang = null;
    public boolean isCut = false;
    public boolean isDouble = false;
    public boolean isRTL = Urls.isRtl();
    public boolean isDoubleCoverAlone = false;
    public boolean isCrop = false;
    public boolean isCropSymetry = false;
    public boolean isSmartReflow = false;
    public boolean isEnableSync;
    public String syncRootID;

    public String currentProfile = AppsConfig.IS_LOG ? "BETA" : "LibreraEMB";
    public String rootPath = new File(Environment.getExternalStorageDirectory(), "Librera-EMB").toString();

    transient SharedPreferences sp;



    public long interstitialLoadAdTime = 0;
    public long interstitialAdShowTime = 0;

    public long rewardedAdLoadedTime = 0;
    public long rewardShowTime = 0;

    public static AppSP get() {
        return instance;
    }

    public void init(Context c) {
        sp = c.getSharedPreferences("AppTemp", Context.MODE_PRIVATE);
        load();

        // On Android 11+ without MANAGE_EXTERNAL_STORAGE, Android6.canWrite() returns
        // false even though getExternalFilesDir() is always writable without any special
        // permission. Redirect rootPath in memory so AppProfile can load/save JSON.
        // Not persisted — if the user later grants the permission the original external
        // path is used on next launch.
        if (!Android6.canWrite(c)) {
            File fallback = c.getExternalFilesDir("Librera-EMB");
            if (fallback == null) {
                fallback = new File(c.getFilesDir(), "Librera-EMB");
            }
            rootPath = fallback.getAbsolutePath();
        }
    }

    public void load() {
        Objects.loadFromSp(instance, sp);

    }

    public void save() {
        Objects.saveToSP(instance, sp);

    }


}
