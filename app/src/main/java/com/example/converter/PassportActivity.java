package com.example.converter;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.IntConsumer;

/** পাসপোর্ট সাইজ ফটো: ক্রপ + ব্যাকগ্রাউন্ড (সাদা / আকাশি) + একটা ফটো বা A4 শিট সেভ */
public class PassportActivity extends Activity {
    static final int ACCENT = 0xFFFFD166;
    static final int SKY = 0xFF87CEEB;
    // A4 @ 300 DPI
    static final int PAGE_W = 2480, PAGE_H = 3508, MARGIN = 59, GAP = 24;

    ScrollView sv;
    PassportCropView cropView;
    ImageView resultView;
    ProgressBar spin;
    TextView hint, tvCopies, status;
    LinearLayout resultCard;
    Button cropBtn;

    Bitmap source, cropped, finalPhoto;
    float[] mask;
    boolean is35 = true, sky = false;
    int copies = 21;
    Segmenter segmenter;
    Bitmap srcMask;           // পুরো ছবির mask (grayscale)
    boolean segRunning, segFailed, pendingCrop;
    int segGen = 0;
    int edge = 50;            // 0..100, কিনারা কতটা ভেতরে টানবে
    boolean online = false;   // true হলে remove.bg API
    boolean maskIsAlpha = false;
    EditText keyEdit;
    LinearLayout keyBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setStatusBarColor(0xFF1A1446);
        getWindow().setNavigationBarColor(0xFF0B3A8C);

        segmenter = Segmentation.getClient(new SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build());
        online = getSharedPreferences("pp", MODE_PRIVATE).getBoolean("online", !BuildConfig.REMOVEBG_KEY.isEmpty());

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1A1446, 0xFF5B1FA8, 0xFF0B6FD6}));
        FrameLayout.LayoutParams o1 = new FrameLayout.LayoutParams(dp(300), dp(300), Gravity.TOP | Gravity.END);
        o1.topMargin = dp(-40);
        o1.rightMargin = dp(-90);
        frame.addView(orb(0xFFFF4D8D, 300), o1);
        FrameLayout.LayoutParams o2 = new FrameLayout.LayoutParams(dp(320), dp(320), Gravity.CENTER_VERTICAL | Gravity.START);
        o2.leftMargin = dp(-140);
        frame.addView(orb(0xFF00E5FF, 320), o2);

        sv = new ScrollView(this);
        sv.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));
        sv.addView(root);
        frame.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        root.addView(TabBar.make(this, 1));

        TextView title = new TextView(this);
        title.setText("পাসপোর্ট ফটো");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);
        TextView sub = body("ক্রপ • ব্যাকগ্রাউন্ড বদল • A4 শিট", 13);
        sub.setPadding(0, 0, 0, dp(16));
        root.addView(sub);

        // --- ছবি
        LinearLayout c1 = card();
        c1.addView(section("📷 ছবি"));
        Button pick = btn("ছবি বাছাই করো", 0xFF00C6FF, 0xFF0072FF);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, 2);
        });
        c1.addView(pick);
        root.addView(c1);

        // --- সাইজ
        LinearLayout c2 = card();
        c2.addView(section("📐 পাসপোর্ট সাইজ"));
        c2.addView(seg("35×45 mm", "2×2 inch", 0, idx -> {
            is35 = (idx == 0);
            cropView.setAspect(photoW(), photoH());
            resultCard.setVisibility(View.GONE);
            clampCopies();
        }));
        root.addView(c2);

        // --- ক্রপ
        LinearLayout c3 = card();
        c3.addView(section("✂️ ক্রপ"));
        c3.addView(body("আঙুল দিয়ে ছবি সরাও, দুই আঙুলে জুম করো। মুখ ফ্রেমের মাঝে রাখো।", 12));
        FrameLayout box = new FrameLayout(this);
        box.setBackground(rounded(0xFF111111, 0x66FFFFFF, 1, 14));
        cropView = new PassportCropView(this);
        cropView.setAspect(photoW(), photoH());
        box.addView(cropView, new FrameLayout.LayoutParams(-1, -1));
        hint = body("আগে ছবি বাছাই করো", 14);
        box.addView(hint, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        spin = new ProgressBar(this);
        spin.setVisibility(View.GONE);
        box.addView(spin, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(440));
        blp.topMargin = dp(10);
        c3.addView(box, blp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(10);
        c3.addView(seg("অফলাইন (ফোনে)", "অনলাইন (remove.bg)", online ? 1 : 0, idx -> {
            online = (idx == 1);
            keyBox.setVisibility(online ? View.VISIBLE : View.GONE);
            getSharedPreferences("pp", MODE_PRIVATE).edit().putBoolean("online", online).apply();
        }), mlp);
        keyBox = new LinearLayout(this);
        keyBox.setOrientation(LinearLayout.VERTICAL);
        keyEdit = new EditText(this);
        keyEdit.setHint("remove.bg API key");
        keyEdit.setSingleLine(true);
        keyEdit.setTextColor(Color.WHITE);
        keyEdit.setHintTextColor(0x88FFFFFF);
        String savedKey = getSharedPreferences("pp", MODE_PRIVATE).getString("key", "");
        keyEdit.setText(savedKey.isEmpty() ? BuildConfig.REMOVEBG_KEY : savedKey);
        if (!BuildConfig.REMOVEBG_KEY.isEmpty()) keyEdit.setVisibility(View.GONE); // key বিল্ডের ভেতরেই আছে
        keyBox.addView(keyEdit);
        keyBox.addView(body(BuildConfig.REMOVEBG_KEY.isEmpty() ? "remove.bg/api থেকে ফ্রি key নাও (মাসে ৫০টা)। ইন্টারনেট লাগবে।" : "remove.bg key বিল্ডে বসানো আছে। ইন্টারনেট লাগবে।", 12));
        keyBox.setVisibility(online ? View.VISIBLE : View.GONE);
        c3.addView(keyBox);
        cropBtn = btn("ক্রপ করো ➜ ব্যাকগ্রাউন্ড বদলাও", 0xFF8E2DE2, 0xFFE100FF);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(10);
        cropBtn.setLayoutParams(clp);
        cropBtn.setOnClickListener(v -> onCrop());
        c3.addView(cropBtn);
        root.addView(c3);

        // --- রেজাল্ট
        resultCard = card();
        resultCard.setVisibility(View.GONE);
        resultCard.addView(section("✅ রেজাল্ট"));
        resultView = new ImageView(this);
        resultView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultView.setBackground(rounded(0x22FFFFFF, 0x66FFFFFF, 1, 14));
        resultView.setPadding(dp(8), dp(8), dp(8), dp(8));
        resultCard.addView(resultView, new LinearLayout.LayoutParams(-1, dp(320)));

        TextView l1 = body("ব্যাকগ্রাউন্ড", 13);
        l1.setPadding(0, dp(12), 0, dp(6));
        resultCard.addView(l1);
        resultCard.addView(seg("সাদা (White)", "আকাশি (Sky Blue)", 0, idx -> {
            sky = (idx == 1);
            refreshResult();
        }));

        TextView l3 = body("কিনারা ঠিক করো — শুধু অফলাইন মোডে (দাগ থাকলে ডানে, চুল/কাঁধ কাটা গেলে বামে)", 12);
        l3.setPadding(0, dp(12), 0, dp(2));
        resultCard.addView(l3);
        SeekBar edgeBar = new SeekBar(this);
        edgeBar.setMax(100);
        edgeBar.setProgress(edge);
        edgeBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        edgeBar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        edgeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) { }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                edge = sb.getProgress();
                refreshResult();
            }
        });
        resultCard.addView(edgeBar);

        TextView l2 = body("A4 শিটে কপির সংখ্যা", 13);
        l2.setPadding(0, dp(12), 0, dp(6));
        resultCard.addView(l2);
        LinearLayout stepper = new LinearLayout(this);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = btn("−", 0x33FFFFFF, 0x33FFFFFF);
        Button plus = btn("+", 0x33FFFFFF, 0x33FFFFFF);
        tvCopies = body("", 15);
        tvCopies.setGravity(Gravity.CENTER);
        minus.setOnClickListener(v -> { copies--; clampCopies(); });
        plus.setOnClickListener(v -> { copies++; clampCopies(); });
        stepper.addView(minus, new LinearLayout.LayoutParams(dp(64), -2));
        stepper.addView(tvCopies, new LinearLayout.LayoutParams(0, -2, 1f));
        stepper.addView(plus, new LinearLayout.LayoutParams(dp(64), -2));
        resultCard.addView(stepper);

        LinearLayout saveRow = new LinearLayout(this);
        Button saveOne = btn("১টা ফটো সেভ", 0xFF11998E, 0xFF38EF7D);
        LinearLayout.LayoutParams s1 = new LinearLayout.LayoutParams(0, -2, 1f);
        s1.rightMargin = dp(6);
        saveOne.setLayoutParams(s1);
        saveOne.setOnClickListener(v -> saveOne());
        Button saveSheet = btn("A4 শিট সেভ", 0xFFFF512F, 0xFFF09819);
        LinearLayout.LayoutParams s2 = new LinearLayout.LayoutParams(0, -2, 1f);
        s2.leftMargin = dp(6);
        saveSheet.setLayoutParams(s2);
        saveSheet.setOnClickListener(v -> saveSheet());
        saveRow.addView(saveOne);
        saveRow.addView(saveSheet);
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-1, -2);
        srl.topMargin = dp(14);
        resultCard.addView(saveRow, srl);

        status = body("সেভ হবে: Pictures/PassportPhoto", 12);
        status.setPadding(0, dp(8), 0, 0);
        resultCard.addView(status);
        root.addView(resultCard);

        clampCopies();
        setContentView(frame);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (segmenter != null) segmenter.close();
    }

    // ---------- সাইজ ----------
    int photoW() { return is35 ? 413 : 600; }   // 35 mm / 2 inch @300dpi
    int photoH() { return is35 ? 531 : 600; }   // 45 mm / 2 inch @300dpi
    int maxCols() { return (PAGE_W - 2 * MARGIN + GAP) / (photoW() + GAP); }
    int maxRows() { return (PAGE_H - 2 * MARGIN + GAP) / (photoH() + GAP); }
    int maxCopies() { return maxCols() * maxRows(); }

    void clampCopies() {
        int mx = maxCopies();
        copies = Math.max(1, Math.min(copies, mx));
        tvCopies.setText(copies + "  (সর্বোচ্চ " + mx + ")");
    }

    // ---------- ছবি লোড ----------
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != 2 || res != RESULT_OK || data == null || data.getData() == null) return;
        loadImage(data.getData());
    }

    void loadImage(Uri uri) {
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
            Bitmap bmp = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int m = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                if (m > 2400) {
                    float f = 2400f / m;
                    decoder.setTargetSize(Math.round(info.getSize().getWidth() * f),
                            Math.round(info.getSize().getHeight() * f));
                }
            });
            source = bmp;
            cropView.setBitmap(bmp);
            cropView.setAspect(photoW(), photoH());
            hint.setVisibility(View.GONE);
            resultCard.setVisibility(View.GONE);
            srcMask = null;
            segFailed = false;
            segRunning = false;
            pendingCrop = false;
            segGen++;
            spin.setVisibility(View.GONE);
            if (!online) startSegmentation(bmp);
        } catch (Exception e) {
            toast("ছবি খোলা যায়নি");
        }
    }

    // ---------- ক্রপ + ব্যাকগ্রাউন্ড আলাদা ----------
    /** ছবি বাছাই করলেই পুরো ছবিতে ব্যাকগ্রাউন্ড আলাদা করা শুরু (মডেল পুরো মানুষটাকে দেখতে পায়, তাই ভালো হয়) */
    void startSegmentation(final Bitmap bmp) {
        srcMask = null;
        segFailed = false;
        segRunning = true;
        pendingCrop = false;
        final int gen = ++segGen;
        spin.setVisibility(View.VISIBLE);
        int m = Math.max(bmp.getWidth(), bmp.getHeight());
        final Bitmap small = (m > 1024)
                ? Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * 1024f / m),
                        Math.round(bmp.getHeight() * 1024f / m), true)
                : bmp;
        segmenter.process(InputImage.fromBitmap(small, 0))
                .addOnSuccessListener(mk -> {
                    if (gen != segGen) return;
                    ByteBuffer bb = mk.getBuffer();
                    bb.rewind();
                    FloatBuffer fb = bb.asFloatBuffer();
                    int mw = mk.getWidth(), mh = mk.getHeight();
                    int n = mw * mh;
                    float[] arr = new float[n];
                    fb.get(arr, 0, Math.min(n, fb.remaining()));
                    int[] px = new int[n];
                    for (int i = 0; i < n; i++) {
                        int g = Math.round(Math.max(0f, Math.min(1f, arr[i])) * 255f);
                        px[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
                    }
                    srcMask = Bitmap.createBitmap(px, mw, mh, Bitmap.Config.ARGB_8888);
                    segRunning = false;
                    spin.setVisibility(View.GONE);
                    if (pendingCrop) onCrop();
                })
                .addOnFailureListener(e -> {
                    if (gen != segGen) return;
                    segFailed = true;
                    segRunning = false;
                    spin.setVisibility(View.GONE);
                    toast("ব্যাকগ্রাউন্ড আলাদা করা যায়নি, শুধু ক্রপ হবে");
                    if (pendingCrop) onCrop();
                });
    }

    void onCrop() {
        if (source == null) {
            toast("আগে ছবি বাছাই করো");
            return;
        }
        if (online) {
            onlineCrop();
            return;
        }
        if (!segRunning && srcMask == null && !segFailed) {
            startSegmentation(source);
            pendingCrop = true;
            return;
        }
        if (segRunning) {
            pendingCrop = true;
            spin.setVisibility(View.VISIBLE);
            toast("একটু অপেক্ষা করো, ব্যাকগ্রাউন্ড বিশ্লেষণ চলছে");
            return;
        }
        pendingCrop = false;
        int w = photoW(), h = photoH();
        Bitmap bmp = cropView.getCrop(w, h);
        if (bmp == null) return;
        cropped = bmp;
        mask = null;
        maskIsAlpha = false;
        if (srcMask != null) {
            Bitmap mb = cropView.getCropOf(srcMask, w, h);
            if (mb != null) {
                int[] px = new int[w * h];
                mb.getPixels(px, 0, w, 0, 0, w, h);
                mask = new float[w * h];
                for (int i = 0; i < px.length; i++) mask[i] = ((px[i] >> 16) & 0xFF) / 255f;
            }
        }
        cropDone();
    }

    // ---------- অনলাইন (remove.bg) ----------
    void onlineCrop() {
        final String key = keyEdit.getText().toString().trim();
        if (key.isEmpty()) {
            toast("আগে remove.bg API key বসাও");
            return;
        }
        getSharedPreferences("pp", MODE_PRIVATE).edit().putString("key", key).apply();
        final Bitmap bmp = cropView.getCrop(photoW(), photoH());
        if (bmp == null) return;
        spin.setVisibility(View.VISIBLE);
        cropBtn.setEnabled(false);
        new Thread(() -> {
            float[] m = null;
            String err = null;
            try {
                m = removeBgMask(bmp, key);
            } catch (Exception e) {
                err = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            }
            final float[] fm = m;
            final String fe = err;
            runOnUiThread(() -> {
                spin.setVisibility(View.GONE);
                cropBtn.setEnabled(true);
                if (fm == null) {
                    toast("অনলাইন ব্যর্থ: " + fe);
                    return;
                }
                cropped = bmp;
                mask = fm;
                maskIsAlpha = true;
                cropDone();
            });
        }).start();
    }

    /** ক্রপ করা ছবি remove.bg-তে পাঠিয়ে alpha থেকে mask বানায় */
    float[] removeBgMask(Bitmap bmp, String key) throws Exception {
        ByteArrayOutputStream jb = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, jb);
        byte[] jpg = jb.toByteArray();
        String boundary = "----pp" + System.currentTimeMillis();
        String nl = "\r\n";
        HttpURLConnection con = (HttpURLConnection) new URL("https://api.remove.bg/v1.0/removebg").openConnection();
        con.setConnectTimeout(20000);
        con.setReadTimeout(60000);
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("X-Api-Key", key);
        con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream os = con.getOutputStream()) {
            os.write(("--" + boundary + nl + "Content-Disposition: form-data; name=\"size\"" + nl + nl + "auto" + nl).getBytes("UTF-8"));
            os.write(("--" + boundary + nl + "Content-Disposition: form-data; name=\"format\"" + nl + nl + "png" + nl).getBytes("UTF-8"));
            os.write(("--" + boundary + nl + "Content-Disposition: form-data; name=\"image_file\"; filename=\"photo.jpg\"" + nl
                    + "Content-Type: image/jpeg" + nl + nl).getBytes("UTF-8"));
            os.write(jpg);
            os.write((nl + "--" + boundary + "--" + nl).getBytes("UTF-8"));
        }
        int code = con.getResponseCode();
        if (code != 200) {
            String body = new String(readBytes(con.getErrorStream()), "UTF-8");
            Matcher mt = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
            throw new Exception("HTTP " + code + (mt.find() ? ": " + mt.group(1) : ""));
        }
        byte[] png = readBytes(con.getInputStream());
        Bitmap res = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (res == null) throw new Exception("রেজাল্ট পড়া যায়নি");
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (res.getWidth() != w || res.getHeight() != h) res = Bitmap.createScaledBitmap(res, w, h, true);
        int[] px = new int[w * h];
        res.getPixels(px, 0, w, 0, 0, w, h);
        float[] m = new float[w * h];
        for (int i = 0; i < px.length; i++) m[i] = ((px[i] >>> 24) & 0xFF) / 255f;
        return m;
    }

    byte[] readBytes(InputStream is) throws Exception {
        if (is == null) return new byte[0];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toByteArray();
    }

    void cropDone() {
        spin.setVisibility(View.GONE);
        cropBtn.setEnabled(true);
        refreshResult();
        resultCard.setVisibility(View.VISIBLE);
        clampCopies();
        sv.post(() -> sv.smoothScrollTo(0, resultCard.getTop()));
    }

    float[] resizeMask(float[] src, int sw, int sh, int dw, int dh) {
        if (sw == dw && sh == dh) return src;
        float[] out = new float[dw * dh];
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(y * sh / dh, sh - 1);
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(x * sw / dw, sw - 1);
                out[y * dw + x] = src[sy * sw + sx];
            }
        }
        return out;
    }

    void refreshResult() {
        if (cropped == null) return;
        finalPhoto = (mask == null) ? cropped : composite(cropped, mask, sky ? SKY : Color.WHITE);
        resultView.setImageBitmap(finalPhoto);
    }

    Bitmap composite(Bitmap fg, float[] mk, int bg) {
        int w = fg.getWidth(), h = fg.getHeight();
        int[] px = new int[w * h];
        fg.getPixels(px, 0, w, 0, 0, w, h);
        int br = Color.red(bg), bgG = Color.green(bg), bb = Color.blue(bg);
        float lo = 0.2f + 0.6f * edge / 100f;
        for (int i = 0; i < px.length; i++) {
            float a = maskIsAlpha ? mk[i] : (mk[i] - lo) / 0.3f;
            a = Math.max(0f, Math.min(1f, a));
            if (!maskIsAlpha) a = a * a * (3f - 2f * a);   // কিনারা একটু মসৃণ
            int p = px[i];
            int r = (int) (Color.red(p) * a + br * (1f - a));
            int g = (int) (Color.green(p) * a + bgG * (1f - a));
            int bl = (int) (Color.blue(p) * a + bb * (1f - a));
            px[i] = Color.rgb(r, g, bl);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    // ---------- A4 শিট ----------
    Bitmap buildSheet(Bitmap photo, int n) {
        int w = photo.getWidth(), h = photo.getHeight();
        int rows = (int) Math.ceil(n / (float) maxCols());
        int cols = (int) Math.ceil(n / (float) rows);
        Bitmap sheet = Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sheet);
        c.drawColor(Color.WHITE);
        int totalW = cols * w + (cols - 1) * GAP;
        int totalH = rows * h + (rows - 1) * GAP;
        int x0 = (PAGE_W - totalW) / 2;
        int y0 = (PAGE_H - totalH) / 2;
        Paint line = new Paint();
        line.setColor(0xFFBBBBBB);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1f);
        for (int i = 0; i < n; i++) {
            int r = i / cols, col = i % cols;
            float x = x0 + col * (w + GAP);
            float y = y0 + r * (h + GAP);
            c.drawBitmap(photo, x, y, null);
            c.drawRect(x, y, x + w, y + h, line);   // কাটার দাগ
        }
        return sheet;
    }

    // ---------- সেভ ----------
    void saveOne() {
        if (finalPhoto == null) return;
        toastSaved(saveJpeg(finalPhoto, "passport_" + System.currentTimeMillis()));
    }

    void saveSheet() {
        if (finalPhoto == null) return;
        clampCopies();
        final Bitmap p = finalPhoto;
        final int n = copies;
        status.setText("শিট বানানো হচ্ছে…");
        new Thread(() -> {
            boolean ok;
            try {
                Bitmap sheet = buildSheet(p, n);
                ok = saveJpeg(sheet, "passport_sheet_" + System.currentTimeMillis());
                sheet.recycle();
            } catch (Throwable t) {
                ok = false;
            }
            final boolean r = ok;
            runOnUiThread(() -> toastSaved(r));
        }).start();
    }

    void toastSaved(boolean ok) {
        status.setText(ok ? "সেভ হয়েছে: Pictures/PassportPhoto" : "সেভ করা যায়নি");
        toast(ok ? "গ্যালারির Pictures/PassportPhoto ফোল্ডারে সেভ হয়েছে" : "সেভ করা যায়নি");
    }

    boolean saveJpeg(Bitmap bmp, String name) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PassportPhoto");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return false;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) return false;
                bmp.compress(Bitmap.CompressFormat.JPEG, 100, os);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ---------- UI helper (Converter-এর স্টাইলের সাথে মেলানো) ----------
    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    GradientDrawable rounded(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    View orb(int color, int sizeDp) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(sizeDp) / 2f);
        g.setColors(new int[]{(color & 0x00FFFFFF) | 0xB0000000, color & 0x00FFFFFF});
        v.setBackground(g);
        return v;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(rounded(0x30FFFFFF, 0x66FFFFFF, 1, 22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    TextView section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(15);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(ACCENT);
        v.setPadding(0, 0, 0, dp(8));
        return v;
    }

    TextView body(String t, int sp) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(0xDDFFFFFF);
        return v;
    }

    Button btn(String t, int c1, int c2) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{c1, c2});
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), 0x55FFFFFF);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), g, rounded(0xFFFFFFFF, 0, 0, 16)));
        return b;
    }

    /** দুই অপশনের টগল (segmented) */
    LinearLayout seg(String a, String b, int sel, IntConsumer cb) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.setBackground(rounded(0x30FFFFFF, 0x66FFFFFF, 1, 18));
        final TextView[] t = new TextView[2];
        String[] labels = {a, b};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            TextView v = new TextView(this);
            v.setText(labels[i]);
            v.setGravity(Gravity.CENTER);
            v.setTextSize(14);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setPadding(dp(6), dp(10), dp(6), dp(10));
            t[i] = v;
            v.setOnClickListener(x -> {
                paintSeg(t, idx);
                cb.accept(idx);
            });
            row.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        paintSeg(t, sel);
        return row;
    }

    void paintSeg(TextView[] t, int sel) {
        for (int i = 0; i < t.length; i++) {
            if (i == sel) {
                t[i].setBackground(rounded(ACCENT, 0, 0, 14));
                t[i].setTextColor(0xFF1A1446);
            } else {
                t[i].setBackground(null);
                t[i].setTextColor(Color.WHITE);
            }
        }
    }
}
