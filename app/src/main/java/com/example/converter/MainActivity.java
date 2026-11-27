package com.example.converter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.ImageDecoder;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

public class MainActivity extends Activity {
    static final int FMT_JPG = 0, FMT_PNG = 1, FMT_WEBP = 2, FMT_PDF = 3, FMT_DOCX = 4;
    static final int K_IMG = 0, K_PDF = 1, K_DOCX = 2, K_XLSX = 3, K_BAD = 4;
    final List<Uri> picked = new ArrayList<>();
    TextView status, listView;
    LinearLayout fileList;
    Spinner fmtSpinner, unitSpinner, modeSpinner;
    int mode = 0;
    EditText sizeEdit;
    Button convertBtn, shareBtn;
    final List<Uri> outUris = new ArrayList<>();
    final List<String> outMimes = new ArrayList<>();
    ProgressBar bar;
    int[] crop; // {x, y, w, h} in (rotated) image pixels, null = no crop
    final ArrayList<Integer> ops = new ArrayList<>(); // transforms applied before crop: 0 = rotate 90 CW, 1 = flip horizontal, 2 = flip vertical
    TextView cropLabel;
    EditText outWEdit, outHEdit;
    CheckBox keepRatio;
    int outPxW, outPxH;
    boolean keepRatioOn = true;
    int origW, origH; // base size shown in pixel fields (first image, after crop/rotate)
    boolean pxUpdating = false;

    static final int ACCENT = 0xFFFFD166;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setStatusBarColor(0xFF1A1446);
        getWindow().setNavigationBarColor(0xFF0B3A8C);
        getWindow().getDecorView().setSystemUiVisibility(0);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1A1446, 0xFF5B1FA8, 0xFF0B6FD6}));
        FrameLayout.LayoutParams o1 = new FrameLayout.LayoutParams(dp(300), dp(300), Gravity.TOP | Gravity.END);
        o1.topMargin = dp(-40); o1.rightMargin = dp(-90);
        frame.addView(orb(0xFFFF4D8D, 300), o1);
        FrameLayout.LayoutParams o2 = new FrameLayout.LayoutParams(dp(320), dp(320), Gravity.CENTER_VERTICAL | Gravity.START);
        o2.leftMargin = dp(-140);
        frame.addView(orb(0xFF00E5FF, 320), o2);
        FrameLayout.LayoutParams o3 = new FrameLayout.LayoutParams(dp(280), dp(280), Gravity.BOTTOM | Gravity.END);
        o3.bottomMargin = dp(-60); o3.rightMargin = dp(-60);
        frame.addView(orb(0xFFFFA000, 280), o3);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));
        sv.addView(root);
        frame.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        root.addView(TabBar.make(this, 0));

        TextView title = new TextView(this);
        title.setText("Offline Converter");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);
        TextView sub = body("ছবি • PDF • DOCX • Excel কনভার্টার", 13);
        sub.setPadding(0, 0, 0, dp(16));
        root.addView(sub);

        // --- File card
        LinearLayout c1 = card();
        c1.addView(section("📁 ফাইল"));
        Button pick = btn("ফাইল বাছাই করো (ছবি / PDF / DOCX / Excel)", 0xFF00C6FF, 0xFF0072FF);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, 1);
        });
        c1.addView(pick);
        Button gallery = btn("🖼 গ্যালারি / অন্য অ্যাপ থেকে ছবি বাছাই করো", 0xFF11998E, 0xFF38EF7D);
        gallery.setOnClickListener(v -> {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.addCategory(Intent.CATEGORY_OPENABLE);
            g.setType("image/*");
            g.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(g, "ছবি কোথা থেকে বাছবে?"), 1);
        });
        c1.addView(gallery);
        Button clearAll = btn("🗑 সব ফাইল মুছো", 0xFFFF5F6D, 0xFFFFC371);
        clearAll.setOnClickListener(v -> { picked.clear(); showPicked(); });
        c1.addView(clearAll);
        listView = body("কোনো ফাইল বাছাই হয়নি", 14);
        listView.setPadding(0, dp(10), 0, 0);
        c1.addView(listView);
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        c1.addView(fileList);
        root.addView(c1);

        // --- Format card
        LinearLayout c2 = card();
        c2.addView(section("🖼️ আউটপুট ফরম্যাট"));
        fmtSpinner = new Spinner(this);
        styleSpinner(fmtSpinner, new String[]{"JPG", "PNG", "WebP", "PDF (সব মিলিয়ে একটা)", "DOCX (সব মিলিয়ে একটা)"});
        c2.addView(fmtSpinner);
        root.addView(c2);

        // --- Size card
        LinearLayout c3 = card();
        c3.addView(section("📦 সাইজ লিমিট (খালি রাখলে লিমিট নেই)"));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        sizeEdit = numEdit();
        sizeEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        sizeEdit.setHint("যেমন 20");
        sizeEdit.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        unitSpinner = new Spinner(this);
        styleSpinner(unitSpinner, new String[]{"KB", "MB"});
        row.addView(sizeEdit);
        row.addView(unitSpinner);
        c3.addView(row);
        TextView l3 = body("সাইজ মোড", 13);
        l3.setPadding(0, dp(12), 0, 0);
        c3.addView(l3);
        modeSpinner = new Spinner(this);
        styleSpinner(modeSpinner, new String[]{"ছোট করো (এই সাইজের মধ্যে)", "বড় করো (কমপক্ষে এই সাইজ)", "ঠিক এই সাইজ (ছোট/বড় দুটোই)"});
        c3.addView(modeSpinner);
        TextView note = body("প্রতিটি আউটপুট ফাইল (PDF/DOCX হলে পুরো ফাইল) এই সাইজ ধরে হবে। \"বড় করো\" মোডে ফাইলে অতিরিক্ত ডেটা যোগ হয় — ছবির কোয়ালিটি বাড়ে না।", 12);
        note.setPadding(0, dp(8), 0, 0);
        c3.addView(note);
        root.addView(c3);

        // --- Crop card
        LinearLayout c4 = card();
        c4.addView(section("✂️ ক্রপ (শুধু ছবির জন্য, pixel হিসাবে)"));
        LinearLayout cropRow = new LinearLayout(this);
        Button cropBtn = btn("ক্রপ সেট করো", 0xFF8E2DE2, 0xFFE100FF);
        LinearLayout.LayoutParams lpa = new LinearLayout.LayoutParams(0, -2, 1f);
        lpa.rightMargin = dp(6);
        cropBtn.setLayoutParams(lpa);
        cropBtn.setOnClickListener(v -> showCropDialog());
        Button cropClr = btn("ক্রপ বাদ দাও", 0x33FFFFFF, 0x33FFFFFF);
        LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(0, -2, 1f);
        lpb.leftMargin = dp(6);
        cropClr.setLayoutParams(lpb);
        cropClr.setOnClickListener(v -> { crop = null; ops.clear(); updateCropLabel(); });
        cropRow.addView(cropBtn);
        cropRow.addView(cropClr);
        c4.addView(cropRow);
        cropLabel = body("", 12);
        cropLabel.setPadding(0, dp(8), 0, 0);
        c4.addView(cropLabel);
        updateCropLabel();
        root.addView(c4);

        // --- Output pixel card
        LinearLayout c5 = card();
        c5.addView(section("📐 আউটপুট ছবির সাইজ (pixel)"));
        c5.addView(body("অরিজিনাল pixel দেখানো আছে — বদলালে বদলাবে, রিসেট চাপলে আবার অরিজিনাল", 12));
        LinearLayout pxRow = new LinearLayout(this);
        outWEdit = numEdit();
        outWEdit.setHint("Width px");
        outHEdit = numEdit();
        outHEdit.setHint("Height px");
        pxRow.addView(column("Width", outWEdit));
        pxRow.addView(column("Height", outHEdit));
        c5.addView(pxRow);
        keepRatio = new CheckBox(this);
        keepRatio.setText("Aspect ratio ঠিক রাখো (একটা দিলে অন্যটা নিজে হবে)");
        keepRatio.setTextColor(Color.WHITE);
        keepRatio.setTextSize(13);
        keepRatio.setButtonTintList(ColorStateList.valueOf(ACCENT));
        keepRatio.setChecked(true);
        c5.addView(keepRatio);
        Button pxClr = btn("রিসেট (অরিজিনাল পিক্সেল)", 0x33FFFFFF, 0x33FFFFFF);
        LinearLayout.LayoutParams lpx = new LinearLayout.LayoutParams(-1, -2);
        lpx.topMargin = dp(8);
        pxClr.setLayoutParams(lpx);
        pxClr.setOnClickListener(v -> setPxFields(origW, origH));
        outWEdit.addTextChangedListener(ratioWatcher(true));
        outHEdit.addTextChangedListener(ratioWatcher(false));
        c5.addView(pxClr);
        root.addView(c5);

        convertBtn = btn("কনভার্ট করো", 0xFFFF512F, 0xFFF09819);
        convertBtn.setTextSize(17);
        convertBtn.setOnClickListener(v -> startConvert());
        root.addView(convertBtn);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x44FFFFFF));
        LinearLayout.LayoutParams lpbar = new LinearLayout.LayoutParams(-1, -2);
        lpbar.topMargin = dp(12);
        bar.setLayoutParams(lpbar);
        root.addView(bar);
        status = body("আউটপুট যাবে: Downloads/Converter", 13);
        root.addView(status);

        shareBtn = btn("কনভার্ট হওয়া ফাইল শেয়ার করো", 0xFF11998E, 0xFF38EF7D);
        shareBtn.setVisibility(View.GONE);
        shareBtn.setOnClickListener(v -> shareOutputs());
        LinearLayout.LayoutParams lps = new LinearLayout.LayoutParams(-1, -2);
        lps.topMargin = dp(10);
        shareBtn.setLayoutParams(lps);
        root.addView(shareBtn);
        setContentView(frame);
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    void handleShare(Intent in) {
        if (in == null) return;
        String a = in.getAction();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u == null && in.getClipData() != null && in.getClipData().getItemCount() > 0)
                u = in.getClipData().getItemAt(0).getUri();
            if (u != null) addPicked(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if ((l == null || l.isEmpty()) && in.getClipData() != null) {
                l = new ArrayList<>();
                for (int i = 0; i < in.getClipData().getItemCount(); i++) {
                    Uri cu = in.getClipData().getItemAt(i).getUri();
                    if (cu != null) l.add(cu);
                }
            }
            if (l != null && !l.isEmpty()) { for (Uri x : l) addPicked(x); }
        } else return;
        showPicked();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != 1 || res != RESULT_OK || data == null) return;
        // আগের বাছাই করা ফাইল রেখে নতুনগুলো যোগ হবে (ডুপ্লিকেট বাদ)
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                addPicked(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) addPicked(data.getData());
        showPicked();
    }

    void addPicked(Uri u) {
        if (u != null && !picked.contains(u)) picked.add(u);
    }

    void showPicked() {
        crop = null;
        ops.clear();
        updateCropLabel();
        fileList.removeAllViews();
        if (picked.isEmpty()) { listView.setText("কোনো ফাইল বাছাই হয়নি"); return; }
        listView.setText(picked.size() + "টি ফাইল বাছাই হয়েছে (ভুল ফাইল বাদ দিতে ✕ চাপো):");
        for (int idx = 0; idx < picked.size(); idx++) {
            final Uri u = picked.get(idx);
            String line = "• " + displayName(u);
            if (kind(u) == K_IMG) {
                int[] d = imageSize(u);
                if (d != null) line += "  —  " + d[0] + " × " + d[1] + " px";
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView name = body(line, 14);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView x = new TextView(this);
            x.setText("✕");
            x.setTextSize(18);
            x.setTextColor(0xFFFF6B6B);
            x.setTypeface(Typeface.DEFAULT_BOLD);
            x.setGravity(android.view.Gravity.CENTER);
            x.setPadding(dp(14), dp(8), dp(14), dp(8));
            x.setOnClickListener(v -> { picked.remove(u); showPicked(); });
            row.addView(x);
            row.setPadding(0, dp(2), 0, dp(2));
            fileList.addView(row);
        }
    }

    /** Image pixel size (width, height) with EXIF rotation applied, or null if unknown. */
    int[] imageSize(Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            try (InputStream in2 = getContentResolver().openInputStream(u)) {
                int ori = new android.media.ExifInterface(in2).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_90
                        || ori == android.media.ExifInterface.ORIENTATION_ROTATE_270
                        || ori == android.media.ExifInterface.ORIENTATION_TRANSPOSE
                        || ori == android.media.ExifInterface.ORIENTATION_TRANSVERSE) {
                    int t = w; w = h; h = t;
                }
            } catch (Exception ignore) { }
            return new int[]{w, h};
        } catch (Exception e) {
            return null;
        }
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    void setPxFields(int w, int h) {
        pxUpdating = true;
        outWEdit.setText(w > 0 ? String.valueOf(w) : "");
        outHEdit.setText(h > 0 ? String.valueOf(h) : "");
        pxUpdating = false;
    }

    TextWatcher ratioWatcher(final boolean isW) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                if (pxUpdating || origW <= 0 || origH <= 0 || !keepRatio.isChecked()) return;
                try {
                    int v = Integer.parseInt(s.toString().trim());
                    pxUpdating = true;
                    if (isW) outHEdit.setText(String.valueOf(Math.max(1, (int) Math.round(v * origH / (double) origW))));
                    else outWEdit.setText(String.valueOf(Math.max(1, (int) Math.round(v * origW / (double) origH))));
                } catch (Exception ex) {
                    // empty/invalid: leave the other field alone
                } finally { pxUpdating = false; }
            }
        };
    }

    /** Shows the original (or cropped/rotated) pixel size of the first image in the output fields. */
    void refreshPxFields() {
        if (outWEdit == null) return;
        int w = 0, h = 0;
        for (Uri u : picked) if (kind(u) == K_IMG) {
            int[] d = imageSize(u);
            if (d != null) { w = d[0]; h = d[1]; }
            break;
        }
        if (w > 0 && crop != null) { w = crop[2]; h = crop[3]; }
        else if (w > 0) {
            int rot = 0;
            for (int op : ops) if (op == 0) rot++;
            if (rot % 2 == 1) { int t = w; w = h; h = t; }
        }
        origW = w; origH = h;
        setPxFields(w, h);
    }

    void updateCropLabel() {
        if (cropLabel == null) return;
        StringBuilder sb = new StringBuilder();
        if (crop == null) sb.append("ক্রপ: নেই (পুরো ছবি)");
        else sb.append("ক্রপ: ").append(crop[2]).append(" × ").append(crop[3]).append(" px, Position X=")
                .append(crop[0]).append(", Y=").append(crop[1])
                .append("\n(সব ছবিতে এই মাপ প্রয়োগ হবে; ছবির বাইরে গেলে ছবির সীমা পর্যন্ত কাটবে)");
        if (!ops.isEmpty()) sb.append("\nঘোরানো/ফ্লিপ প্রয়োগ করা আছে (").append(ops.size()).append(" ধাপ)");
        cropLabel.setText(sb.toString());
        refreshPxFields();
    }

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

    void styleSpinner(Spinner sp, String[] items) {
        // নিজের TextView বানানো হলো — Samsung One UI-র থিম/ডার্ক মোড যেন লেখার রং না বদলায়
        sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            TextView mk(int pos, boolean drop) {
                TextView t = new TextView(MainActivity.this);
                t.setText(getItem(pos));
                t.setTextColor(0xFFFFFFFF);
                t.setTextSize(15);
                t.setSingleLine(false);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setMinHeight(dp(drop ? 48 : 40));
                t.setForceDarkAllowed(false);
                if (drop) t.setPadding(dp(16), dp(12), dp(16), dp(12));
                else t.setPadding(dp(4), dp(8), dp(28), dp(8));
                return t;
            }

            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                return mk(pos, false);
            }

            @Override
            public View getDropDownView(int pos, View cv, ViewGroup parent) {
                return mk(pos, true);
            }
        });
        sp.setForceDarkAllowed(false);
        sp.setBackgroundTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sp.setPopupBackgroundDrawable(rounded(0xF02A2468, 0x66FFFFFF, 1, 14));
    }

    void darken(View v) {
        if (v instanceof EditText || v instanceof Button) return;
        if (v instanceof TextView) { ((TextView) v).setTextColor(0xDDFFFFFF); return; }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) darken(g.getChildAt(i));
        }
    }

    EditText numEdit() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0x88FFFFFF);
        e.setTextSize(16);
        e.setBackground(rounded(0x26FFFFFF, 0x55FFFFFF, 1, 12));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    LinearLayout column(String label, EditText e) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(4), dp(6), dp(4), dp(6));
        c.setLayoutParams(lp);
        TextView t = body(label, 13);
        t.setPadding(0, 0, 0, dp(4));
        c.addView(t);
        c.addView(e);
        return c;
    }

    void watch(final EditText e, final java.util.function.IntConsumer apply) {
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                if (!e.hasFocus()) return;
                try { apply.accept(Integer.parseInt(s.toString().trim())); } catch (Exception ex) { }
            }
        });
    }

    void showCropDialog() {
        Uri img = null;
        for (Uri u : picked) if (kind(u) == K_IMG) { img = u; break; }
        if (img == null) { Toast.makeText(this, "আগে ছবি বাছাই করো", Toast.LENGTH_SHORT).show(); return; }
        final int[] sz = new int[2];
        Bitmap prev;
        try {
            prev = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), img), (dec, info, src) -> {
                dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                sz[0] = w; sz[1] = h;
                int m = Math.max(w, h);
                if (m > 1600) {
                    float f = 1600f / m;
                    dec.setTargetSize(Math.round(w * f), Math.round(h * f));
                }
            });
        } catch (Throwable ex) {
            Toast.makeText(this, "ছবি লোড হয়নি: " + ex, Toast.LENGTH_LONG).show();
            return;
        }
        int w0 = sz[0], h0 = sz[1];
        if (w0 != h0 && (w0 > h0) != (prev.getWidth() > prev.getHeight())) { int t = w0; w0 = h0; h0 = t; }
        final int fw = w0, fh = h0;

        final CropView cv = new CropView(this);
        cv.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(300)));
        cv.setImage(prev, fw, fh);
        final ArrayList<Integer> dOps = new ArrayList<>(ops);
        for (int op : dOps) cv.apply(op);
        if (crop != null) cv.setCrop(crop[0], crop[1], crop[2], crop[3]);

        final EditText eW = numEdit(), eH = numEdit(), eX = numEdit(), eY = numEdit();
        final Runnable sync = () -> {
            int[] c = cv.getCrop();
            if (!eW.hasFocus()) eW.setText(String.valueOf(c[2]));
            if (!eH.hasFocus()) eH.setText(String.valueOf(c[3]));
            if (!eX.hasFocus()) eX.setText(String.valueOf(c[0]));
            if (!eY.hasFocus()) eY.setText(String.valueOf(c[1]));
        };
        int[] c0 = cv.getCrop();
        eW.setText(String.valueOf(c0[2])); eH.setText(String.valueOf(c0[3]));
        eX.setText(String.valueOf(c0[0])); eY.setText(String.valueOf(c0[1]));
        watch(eW, v -> cv.setW(v));
        watch(eH, v -> cv.setH(v));
        watch(eX, v -> cv.setX(v));
        watch(eY, v -> cv.setY(v));
        View.OnFocusChangeListener fix = (v, has) -> { if (!has) sync.run(); };
        eW.setOnFocusChangeListener(fix); eH.setOnFocusChangeListener(fix);
        eX.setOnFocusChangeListener(fix); eY.setOnFocusChangeListener(fix);
        cv.listener = () -> sync.run();

        final String[] names = {"FreeForm", "Original", "1:1", "4:3", "3:4", "16:9", "9:16", "3:2", "2:3"};
        final float[] ratios = {0f, cv.iw / (float) cv.ih, 1f, 4f / 3f, 3f / 4f, 16f / 9f, 9f / 16f, 3f / 2f, 2f / 3f};
        Spinner asp = new Spinner(this);
        styleSpinner(asp, names);
        asp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { cv.setAspect(ratios[pos]); }
            public void onNothingSelected(AdapterView<?> p) { }
        });

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        TextView dTitle = new TextView(this);
        dTitle.setText("✂️ ক্রপ");
        dTitle.setTextSize(20);
        dTitle.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(dTitle);
        TextView info = new TextView(this);
        final Runnable updInfo = () -> info.setText("ছবি: " + cv.iw + " × " + cv.ih + " px"
                + (!dOps.isEmpty() ? " (ঘোরানো/ফ্লিপ: " + dOps.size() + " ধাপ)" : "")
                + " — ছবির উপর টেনে সরাও, কোণ ধরে মাপ বদলাও");
        updInfo.run();
        info.setTextSize(12);
        box.addView(info);
        box.addView(cv);
        LinearLayout tools = new LinearLayout(this);
        LinearLayout.LayoutParams lpt = new LinearLayout.LayoutParams(-1, -2);
        lpt.topMargin = dp(8);
        tools.setLayoutParams(lpt);
        String[] tn = {"🔄 Rotate", "↔ Flip", "↕ Vertical"};
        for (int k = 0; k < 3; k++) {
            final int op = k;
            Button tb = btn(tn[k], k == 0 ? 0xFF00C6FF : (k == 1 ? 0xFF8E2DE2 : 0xFFFF512F),
                    k == 0 ? 0xFF0072FF : (k == 1 ? 0xFFE100FF : 0xFFF09819));
            tb.setTextSize(13);
            tb.setPadding(dp(4), dp(10), dp(4), dp(10));
            LinearLayout.LayoutParams lpb2 = new LinearLayout.LayoutParams(0, -2, 1f);
            lpb2.setMargins(dp(3), 0, dp(3), 0);
            tb.setLayoutParams(lpb2);
            tb.setOnClickListener(v -> {
                cv.apply(op);
                dOps.add(op);
                if (op == 0) {
                    ratios[1] = cv.iw / (float) cv.ih;
                    int sel = asp.getSelectedItemPosition();
                    if (sel > 0) cv.setAspect(ratios[sel]);
                }
                updInfo.run();
            });
            tools.addView(tb);
        }
        box.addView(tools);
        TextView t1 = new TextView(this);
        t1.setText("\nCrop Rectangle");
        t1.setTextSize(16);
        box.addView(t1);
        LinearLayout r1 = new LinearLayout(this);
        r1.addView(column("Width", eW));
        r1.addView(column("Height", eH));
        box.addView(r1);
        TextView t2 = new TextView(this);
        t2.setText("Aspect Ratio");
        box.addView(t2);
        box.addView(asp);
        TextView t3 = new TextView(this);
        t3.setText("\nCrop Position");
        t3.setTextSize(16);
        box.addView(t3);
        LinearLayout r2 = new LinearLayout(this);
        r2.addView(column("Position (X)", eX));
        r2.addView(column("Position (Y)", eY));
        box.addView(r2);
        darken(box);
        dTitle.setTextColor(ACCENT);
        t1.setTextColor(ACCENT);
        t3.setTextColor(ACCENT);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        AlertDialog d = new AlertDialog.Builder(this)
                .setView(sv)
                .setPositiveButton("ঠিক আছে", (dlg, w) -> {
                    int[] c = cv.getCrop();
                    crop = (c[0] == 0 && c[1] == 0 && c[2] >= cv.iw && c[3] >= cv.ih) ? null : c;
                    ops.clear();
                    ops.addAll(dOps);
                    updateCropLabel();
                })
                .setNeutralButton("ক্রপ বাদ", (dlg, w) -> { crop = null; ops.clear(); updateCropLabel(); })
                .setNegativeButton("বাতিল", null)
                .create();
        d.getWindow().setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2A1B6B, 0xFF5B1FA8, 0xFF0B5FC0}) {{ setCornerRadius(dp(24)); setStroke(dp(1), 0x66FFFFFF); }});
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ACCENT);
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFFFFFFFF);
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(0xFFFFFFFF);
        box.requestFocus();
    }

    void say(String s) { runOnUiThread(() -> status.setText(s)); }

    void startConvert() {
        if (picked.isEmpty()) { Toast.makeText(this, "আগে ফাইল বাছাই করো", Toast.LENGTH_SHORT).show(); return; }
        final int fmt = fmtSpinner.getSelectedItemPosition();
        outPxW = parsePx(outWEdit);
        outPxH = parsePx(outHEdit);
        if (outPxW == origW && outPxH == origH) { outPxW = 0; outPxH = 0; } // untouched = keep original
        keepRatioOn = keepRatio.isChecked();
        long target = 0;
        String t = sizeEdit.getText().toString().trim();
        if (!t.isEmpty()) {
            try {
                double v = Double.parseDouble(t);
                target = (long) (v * (unitSpinner.getSelectedItemPosition() == 0 ? 1024 : 1048576));
            } catch (Exception e) { }
        }
        final long tgt = target;
        mode = modeSpinner.getSelectedItemPosition();
        if (mode > 0 && tgt <= 0) {
            Toast.makeText(this, "সাইজ লিখে দাও", Toast.LENGTH_SHORT).show();
            return;
        }
        convertBtn.setEnabled(false);
        shareBtn.setVisibility(View.GONE);
        synchronized (outUris) { outUris.clear(); outMimes.clear(); }
        new Thread(() -> {
            try { run(fmt, tgt); } catch (Throwable e) { say("ভুল: " + e); }
            runOnUiThread(() -> {
                convertBtn.setEnabled(true);
                synchronized (outUris) {
                    if (!outUris.isEmpty()) {
                        shareBtn.setText("শেয়ার করো (" + outUris.size() + "টি ফাইল)");
                        shareBtn.setVisibility(View.VISIBLE);
                    }
                }
            });
        }).start();
    }

    void shareOutputs() {
        final ArrayList<Uri> uris;
        final List<String> mimes;
        synchronized (outUris) { uris = new ArrayList<>(outUris); mimes = new ArrayList<>(outMimes); }
        if (uris.isEmpty()) { Toast.makeText(this, "শেয়ার করার মতো ফাইল নেই", Toast.LENGTH_SHORT).show(); return; }
        String type = mimes.get(0);
        for (String m : mimes) if (!m.equals(type)) { type = "*/*"; break; }
        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND);
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        send.setType(type);
        android.content.ClipData cd = android.content.ClipData.newRawUri("converted", uris.get(0));
        for (int i = 1; i < uris.size(); i++) cd.addItem(new android.content.ClipData.Item(uris.get(i)));
        send.setClipData(cd);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "শেয়ার করো");
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                new android.content.ComponentName[]{new android.content.ComponentName(this, MainActivity.class)});
        startActivity(chooser);
    }

    void run(int fmt, long target) throws Exception {
        List<Uri> uris = new ArrayList<>(picked);
        int[] counts = new int[uris.size()];
        int total = 0;
        for (int k = 0; k < uris.size(); k++) {
            Uri u = uris.get(k);
            int kd = kind(u);
            if (kd == K_PDF) counts[k] = pdfPages(u);
            else if (kd == K_DOCX || kd == K_XLSX) {
                say("পাতা গুনছি: " + baseName(u));
                counts[k] = DocRender.render(readZip(u), kd == K_XLSX, null, true);
            } else if (kd == K_IMG) counts[k] = 1;
            else counts[k] = 0;
            total += counts[k];
        }
        final int tot = Math.max(1, total);
        runOnUiThread(() -> { bar.setMax(tot); bar.setProgress(0); });
        final boolean bundle = fmt == FMT_PDF || fmt == FMT_DOCX;
        final long perPage = bundle
                ? ((target > 0 && mode != 1) ? Math.max(2000, (target - (fmt == FMT_DOCX ? 3500 : 1500)) / tot) : 0)
                : target;
        final List<byte[]> pdfJpgs = new ArrayList<>();
        final List<int[]> pdfDims = new ArrayList<>();
        final int[] cnt = new int[3]; // saved, done, skipped
        for (int k = 0; k < uris.size(); k++) {
            Uri u = uris.get(k);
            int kd = kind(u);
            if (kd == K_BAD) { cnt[2]++; continue; }
            final String base = baseName(u);
            final int nPages = counts[k];
            final int ffmt = fmt;
            if (kd == K_PDF) {
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(u, "r");
                     PdfRenderer r = new PdfRenderer(pfd)) {
                    int n = r.getPageCount();
                    for (int i = 0; i < n; i++) {
                        say("প্রসেস হচ্ছে: " + base + " পাতা " + (i + 1) + "/" + n);
                        PdfRenderer.Page pg = r.openPage(i);
                        float sc = 2f;
                        int m = Math.max(pg.getWidth(), pg.getHeight());
                        if (m * sc > 3000) sc = 3000f / m;
                        Bitmap bm = Bitmap.createBitmap(Math.round(pg.getWidth() * sc), Math.round(pg.getHeight() * sc), Bitmap.Config.ARGB_8888);
                        bm.eraseColor(Color.WHITE);
                        pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        pg.close();
                        cnt[0] += emit(bm, fmt, perPage, base + (n > 1 ? "_p" + (i + 1) : ""), pdfJpgs, pdfDims);
                        final int d = ++cnt[1];
                        runOnUiThread(() -> bar.setProgress(d));
                    }
                }
            } else if (kd == K_DOCX || kd == K_XLSX) {
                say("রেন্ডার হচ্ছে: " + base);
                final int[] idx = {0};
                DocRender.PageSink sink = bm -> {
                    int i = idx[0]++;
                    cnt[0] += emit(bm, ffmt, perPage, base + (nPages > 1 ? "_p" + (i + 1) : ""), pdfJpgs, pdfDims);
                    final int d = ++cnt[1];
                    runOnUiThread(() -> bar.setProgress(d));
                };
                DocRender.render(readZip(u), kd == K_XLSX, sink, false);
            } else {
                say("প্রসেস হচ্ছে: " + base);
                Bitmap bm = resizePx(decode(u));
                cnt[0] += emit(bm, fmt, perPage, base, pdfJpgs, pdfDims);
                final int d = ++cnt[1];
                runOnUiThread(() -> bar.setProgress(d));
            }
        }
        String sk = cnt[2] > 0 ? " (" + cnt[2] + "টি অসমর্থিত ফাইল স্কিপ — শুধু ছবি/PDF/.docx/.xlsx চলে)" : "";
        if (bundle) {
            if (pdfJpgs.isEmpty()) { say("কিছু কনভার্ট হয়নি" + sk); return; }
            byte[] out = fmt == FMT_PDF ? buildPdf(pdfJpgs, pdfDims, 0) : buildDocx(pdfJpgs, pdfDims, 0);
            if (mode > 0 && target > 0 && out.length < target) {
                long pad = target - out.length;
                for (int t = 0; t < 5 && pad > 0; t++) {
                    out = fmt == FMT_PDF ? buildPdf(pdfJpgs, pdfDims, (int) pad) : buildDocx(pdfJpgs, pdfDims, (int) pad);
                    long diff = target - out.length;
                    if (diff == 0) break;
                    pad += diff;
                }
            }
            String outBase = baseName(uris.get(0)) + (uris.size() > 1 ? "_merged" : "");
            if (fmt == FMT_PDF) save(outBase + ".pdf", "application/pdf", out);
            else save(outBase + ".docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", out);
            say("শেষ! " + (out.length / 1024) + " KB — Downloads/Converter" + sk);
        } else {
            say("শেষ! " + cnt[0] + "টি ফাইল সেভ হয়েছে — Downloads/Converter" + sk);
        }
    }

    // returns number of files saved directly (0 for pdf mode)
    int emit(Bitmap bm, int fmt, long target, String name, List<byte[]> pdfJpgs, List<int[]> dims) throws Exception {
        if (fmt == FMT_PDF || fmt == FMT_DOCX) {
            bm = flat(bm);
            byte[] j = encodeFit(bm, FMT_JPG, target);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(j, 0, j.length, o);
            pdfJpgs.add(j);
            dims.add(new int[]{o.outWidth, o.outHeight});
            bm.recycle();
            return 0;
        }
        if (fmt == FMT_JPG) bm = flat(bm);
        byte[] out = encodeFit(bm, fmt, mode == 1 ? 0 : target);
        bm.recycle();
        if (mode > 0) out = padTo(out, fmt, target);
        String ext = fmt == FMT_JPG ? "jpg" : fmt == FMT_PNG ? "png" : "webp";
        String mime = fmt == FMT_JPG ? "image/jpeg" : fmt == FMT_PNG ? "image/png" : "image/webp";
        save(name + "." + ext, mime, out);
        return 1;
    }

    Bitmap flat(Bitmap b) {
        Bitmap o = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(o);
        c.drawColor(Color.WHITE);
        c.drawBitmap(b, 0, 0, null);
        b.recycle();
        return o;
    }

    byte[] comp(Bitmap b, int fmt, int q) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Bitmap.CompressFormat cf;
        if (fmt == FMT_PNG) cf = Bitmap.CompressFormat.PNG;
        else if (fmt == FMT_WEBP)
            cf = android.os.Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
        else cf = Bitmap.CompressFormat.JPEG;
        b.compress(cf, q, o);
        return o.toByteArray();
    }

    byte[] encodeFit(Bitmap src, int fmt, long target) {
        Bitmap bmp = src;
        boolean own = false;
        try {
            for (int i = 0; i < 12; i++) {
                if (fmt == FMT_PNG) {
                    byte[] b = comp(bmp, FMT_PNG, 100);
                    if (target <= 0 || b.length <= target) return b;
                } else {
                    byte[] b = comp(bmp, fmt, target <= 0 ? 90 : 92);
                    if (target <= 0 || b.length <= target) return b;
                    int lo = 5, hi = 92;
                    byte[] best = null;
                    while (lo <= hi) {
                        int mid = (lo + hi) / 2;
                        byte[] t = comp(bmp, fmt, mid);
                        if (t.length <= target) { best = t; lo = mid + 1; } else hi = mid - 1;
                    }
                    if (best != null) return best;
                }
                int nw = (int) (bmp.getWidth() * 0.8), nh = (int) (bmp.getHeight() * 0.8);
                if (nw < 16 || nh < 16) break;
                Bitmap n = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                if (own) bmp.recycle();
                bmp = n;
                own = true;
            }
            return comp(bmp, fmt, fmt == FMT_PNG ? 100 : 5);
        } finally {
            if (own) bmp.recycle();
        }
    }

    Bitmap decode(Uri u) throws IOException {
        final int[] cr = crop;
        final ArrayList<Integer> tops = new ArrayList<>(ops);
        final int[] info = new int[2];
        ImageDecoder.Source s = ImageDecoder.createSource(getContentResolver(), u);
        Bitmap bm = ImageDecoder.decodeBitmap(s, (dec, inf, src) -> {
            dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w = inf.getSize().getWidth(), h = inf.getSize().getHeight();
            info[0] = w; info[1] = h;
            int m = Math.max(w, h);
            float f = 1f;
            if (cr == null) {
                if (m > 4096) f = 4096f / m;
            } else {
                // keep full detail inside the crop area (max 4096 px on the cropped side)
                f = Math.min(1f, 4096f / Math.max(1, Math.max(cr[2], cr[3])));
                f = Math.min(f, 6000f / m);
            }
            if (f < 1f) dec.setTargetSize(Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)));
        });
        int ow = info[0], oh = info[1];
        int bw = bm.getWidth(), bh = bm.getHeight();
        if (ow != oh && (ow > oh) != (bw > bh)) { int t = ow; ow = oh; oh = t; }
        if (!tops.isEmpty()) {
            Matrix mx = new Matrix();
            int turns = 0;
            for (int op : tops) {
                if (op == 0) { mx.postRotate(90f); turns++; }
                else if (op == 1) mx.postScale(-1f, 1f);
                else if (op == 2) mx.postScale(1f, -1f);
            }
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bw, bh, mx, true);
            if (r != bm) bm.recycle();
            bm = r;
            if (turns % 2 == 1) { int t = ow; ow = oh; oh = t; }
            bw = bm.getWidth(); bh = bm.getHeight();
        }
        if (cr == null) return bm;
        double sx = bw / (double) ow, sy = bh / (double) oh;
        int x = Math.max(0, Math.min(cr[0], ow - 1));
        int y = Math.max(0, Math.min(cr[1], oh - 1));
        int w = Math.max(1, Math.min(cr[2], ow - x));
        int h = Math.max(1, Math.min(cr[3], oh - y));
        int px = Math.min(bw - 1, (int) Math.round(x * sx));
        int py = Math.min(bh - 1, (int) Math.round(y * sy));
        int pw = Math.max(1, Math.min(bw - px, (int) Math.round(w * sx)));
        int ph = Math.max(1, Math.min(bh - py, (int) Math.round(h * sy)));
        Bitmap out = Bitmap.createBitmap(bm, px, py, pw, ph);
        if (out != bm) bm.recycle();
        return out;
    }

    int parsePx(EditText e) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return Math.max(0, Math.min(v, 12000));
        } catch (Exception ex) { return 0; }
    }

    Bitmap resizePx(Bitmap b) {
        int tw = outPxW, th = outPxH;
        if (tw <= 0 && th <= 0) return b;
        int w = b.getWidth(), h = b.getHeight();
        int nw, nh;
        if (tw > 0 && th > 0) {
            if (keepRatioOn) {
                double f = Math.min(tw / (double) w, th / (double) h);
                nw = (int) Math.round(w * f); nh = (int) Math.round(h * f);
            } else { nw = tw; nh = th; }
        } else if (tw > 0) {
            nw = tw; nh = keepRatioOn ? (int) Math.round(h * (tw / (double) w)) : h;
        } else {
            nh = th; nw = keepRatioOn ? (int) Math.round(w * (th / (double) h)) : w;
        }
        nw = Math.max(1, nw); nh = Math.max(1, nh);
        if (nw == w && nh == h) return b;
        Bitmap o = Bitmap.createScaledBitmap(b, nw, nh, true);
        if (o != b) b.recycle();
        return o;
    }

    boolean isPdf(Uri u) {
        String t = getContentResolver().getType(u);
        return t != null && t.contains("pdf");
    }

    int pdfPages(Uri u) throws IOException {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(u, "r");
             PdfRenderer r = new PdfRenderer(pfd)) {
            return r.getPageCount();
        }
    }

    String displayName(Uri u) {
        String n = "file";
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.getString(i) != null) n = c.getString(i);
            }
        } catch (Exception e) { }
        return n;
    }

    String baseName(Uri u) {
        String n = displayName(u);
        int d = n.lastIndexOf('.');
        if (d > 0) n = n.substring(0, d);
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return n.isEmpty() ? "file" : n;
    }

    int kind(Uri u) {
        String n = displayName(u).toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return K_PDF;
        if (n.endsWith(".docx")) return K_DOCX;
        if (n.endsWith(".xlsx")) return K_XLSX;
        if (n.endsWith(".doc") || n.endsWith(".xls") || n.endsWith(".odt") || n.endsWith(".ods")) return K_BAD;
        String t = getContentResolver().getType(u);
        if (t != null) {
            if (t.contains("pdf")) return K_PDF;
            if (t.contains("wordprocessingml")) return K_DOCX;
            if (t.contains("spreadsheetml")) return K_XLSX;
            if (t.startsWith("image/")) return K_IMG;
        }
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
                || n.endsWith(".bmp") || n.endsWith(".gif") || n.endsWith(".heic") || n.endsWith(".heif")) return K_IMG;
        if (t == null) return K_IMG;
        return K_BAD;
    }

    Map<String, byte[]> readZip(Uri u) throws IOException {
        Map<String, byte[]> m = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(getContentResolver().openInputStream(u))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName();
                boolean want = n.equals("word/document.xml") || n.equals("word/_rels/document.xml.rels")
                        || n.startsWith("word/media/") || n.equals("xl/sharedStrings.xml")
                        || n.equals("xl/workbook.xml")
                        || (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml"));
                if (e.isDirectory() || !want) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = zis.read(buf)) > 0) bo.write(buf, 0, r);
                if (bo.size() <= 25 * 1024 * 1024) m.put(n, bo.toByteArray());
            }
        }
        return m;
    }

    Uri save(String name, String mime, byte[] data) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Converter");
        Uri out = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (out == null) throw new IOException("সেভ করা যায়নি");
        try (OutputStream os = getContentResolver().openOutputStream(out)) {
            os.write(data);
        }
        synchronized (outUris) { outUris.add(out); outMimes.add(mime); }
        return out;
    }

    // Minimal PDF writer: one JPEG per page, exact size control
    byte[] buildPdf(List<byte[]> jpgs, List<int[]> dims, int pad) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int n = jpgs.size();
        int[] off = new int[3 + 3 * n];
        w(o, "%PDF-1.4\n");
        off[1] = o.size();
        w(o, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        off[2] = o.size();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(3 + 3 * i).append(" 0 R ");
        w(o, "2 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>\nendobj\n");
        for (int i = 0; i < n; i++) {
            int[] d = dims.get(i);
            float pw = 595f, ph = 595f * d[1] / d[0];
            int pg = 3 + 3 * i, ct = pg + 1, im = pg + 2;
            off[pg] = o.size();
            w(o, String.format(Locale.US, "%d 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f] /Resources << /XObject << /Im0 %d 0 R >> >> /Contents %d 0 R >>\nendobj\n", pg, pw, ph, im, ct));
            String cs = String.format(Locale.US, "q %.2f 0 0 %.2f 0 0 cm /Im0 Do Q", pw, ph);
            off[ct] = o.size();
            w(o, ct + " 0 obj\n<< /Length " + cs.length() + " >>\nstream\n" + cs + "\nendstream\nendobj\n");
            off[im] = o.size();
            byte[] j = jpgs.get(i);
            w(o, im + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + d[0] + " /Height " + d[1]
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + j.length + " >>\nstream\n");
            o.write(j);
            w(o, "\nendstream\nendobj\n");
        }
        if (pad >= 2) {
            byte[] pb = new byte[pad];
            Arrays.fill(pb, (byte) 'P');
            pb[0] = '%';
            pb[pad - 1] = '\n';
            o.write(pb);
        }
        int xref = o.size();
        int cnt = 3 + 3 * n;
        w(o, "xref\n0 " + cnt + "\n0000000000 65535 f \n");
        for (int i = 1; i < cnt; i++) w(o, String.format(Locale.US, "%010d 00000 n \n", off[i]));
        w(o, "trailer\n<< /Size " + cnt + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return o.toByteArray();
    }

    byte[] buildDocx(List<byte[]> jpgs, List<int[]> dims, int pad) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        String hdr = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
        put(z, "[Content_Types].xml", hdr + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"bin\" ContentType=\"application/octet-stream\"/>"
                + "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>");
        put(z, "_rels/.rels", hdr + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>");
        StringBuilder rels = new StringBuilder(hdr + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        StringBuilder body = new StringBuilder();
        long maxW = 10466L * 635L, maxH = 15398L * 635L;
        for (int i = 0; i < jpgs.size(); i++) {
            int id = i + 1;
            rels.append("<Relationship Id=\"rId" + id + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image" + id + ".jpg\"/>");
            int[] d = dims.get(i);
            long cx = maxW, cy = cx * d[1] / d[0];
            if (cy > maxH) { cy = maxH; cx = cy * d[0] / d[1]; }
            body.append("<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                    + "<wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/><wp:docPr id=\"" + id + "\" name=\"Picture " + id + "\"/>"
                    + "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                    + "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                    + "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                    + "<pic:nvPicPr><pic:cNvPr id=\"" + id + "\" name=\"img" + id + "\"/><pic:cNvPicPr/></pic:nvPicPr>"
                    + "<pic:blipFill><a:blip r:embed=\"rId" + id + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                    + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                    + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>"
                    + "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
        }
        rels.append("</Relationships>");
        put(z, "word/_rels/document.xml.rels", rels.toString());
        put(z, "word/document.xml", hdr + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"><w:body>"
                + body
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>"
                + "</w:body></w:document>");
        for (int i = 0; i < jpgs.size(); i++) {
            z.putNextEntry(new ZipEntry("word/media/image" + (i + 1) + ".jpg"));
            z.write(jpgs.get(i));
            z.closeEntry();
        }
        if (pad > 0) {
            byte[] zeros = new byte[pad];
            ZipEntry e = new ZipEntry("word/pad.bin");
            e.setMethod(ZipEntry.STORED);
            e.setSize(pad);
            e.setCompressedSize(pad);
            CRC32 crc = new CRC32();
            crc.update(zeros);
            e.setCrc(crc.getValue());
            z.putNextEntry(e);
            z.write(zeros);
            z.closeEntry();
        }
        z.close();
        return bo.toByteArray();
    }

    // Grow a file to (at least) target bytes by appending harmless extra data
    byte[] padTo(byte[] b, int fmt, long target) {
        if (target <= 0 || b.length >= target) return b;
        int pad = (int) (target - b.length);
        if (fmt == FMT_JPG) return Arrays.copyOf(b, b.length + pad);
        if (fmt == FMT_PNG) {
            if (pad < 16 || b.length < 12 || b[b.length - 8] != 'I' || b[b.length - 5] != 'D') return b;
            int len = pad - 12;
            byte[] data = new byte[len];
            data[0] = 'p'; data[1] = 'a'; data[2] = 'd';
            byte[] type = {'t', 'E', 'X', 't'};
            byte[] out = new byte[b.length + pad];
            int ins = b.length - 12;
            System.arraycopy(b, 0, out, 0, ins);
            int q = ins;
            out[q++] = (byte) (len >>> 24); out[q++] = (byte) (len >>> 16); out[q++] = (byte) (len >>> 8); out[q++] = (byte) len;
            System.arraycopy(type, 0, out, q, 4); q += 4;
            System.arraycopy(data, 0, out, q, len); q += len;
            CRC32 c = new CRC32();
            c.update(type); c.update(data);
            long cv = c.getValue();
            out[q++] = (byte) (cv >>> 24); out[q++] = (byte) (cv >>> 16); out[q++] = (byte) (cv >>> 8); out[q++] = (byte) cv;
            System.arraycopy(b, ins, out, q, 12);
            return out;
        }
        if (fmt == FMT_WEBP) {
            if (pad < 10 || b.length < 12 || b[0] != 'R' || b[8] != 'W') return b;
            int s = pad - 8;
            if ((s & 1) == 1) s++;
            int total = 8 + s;
            byte[] out = new byte[b.length + total];
            System.arraycopy(b, 0, out, 0, b.length);
            int q = b.length;
            out[q++] = 'J'; out[q++] = 'U'; out[q++] = 'N'; out[q++] = 'K';
            out[q++] = (byte) s; out[q++] = (byte) (s >>> 8); out[q++] = (byte) (s >>> 16); out[q++] = (byte) (s >>> 24);
            int riff = (b[4] & 0xff) | ((b[5] & 0xff) << 8) | ((b[6] & 0xff) << 16) | ((b[7] & 0xff) << 24);
            riff += total;
            out[4] = (byte) riff; out[5] = (byte) (riff >>> 8); out[6] = (byte) (riff >>> 16); out[7] = (byte) (riff >>> 24);
            return out;
        }
        return b;
    }

    static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes("UTF-8"));
        z.closeEntry();
    }

    static void w(ByteArrayOutputStream o, String s) throws IOException {
        o.write(s.getBytes("ISO-8859-1"));
    }
}
