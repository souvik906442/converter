package com.example.converter;

import android.app.Activity;
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
import android.text.InputType;
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
    Spinner fmtSpinner, unitSpinner, modeSpinner;
    int mode = 0;
    EditText sizeEdit;
    Button convertBtn, shareBtn;
    final List<Uri> outUris = new ArrayList<>();
    final List<String> outMimes = new ArrayList<>();
    ProgressBar bar;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 2, p, p);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("Offline Converter");
        title.setTextSize(24);
        root.addView(title);

        Button pick = new Button(this);
        pick.setText("ফাইল বাছাই করো (ছবি / PDF / DOCX / Excel)");
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, 1);
        });
        root.addView(pick);

        listView = new TextView(this);
        listView.setText("কোনো ফাইল বাছাই হয়নি");
        root.addView(listView);

        TextView l1 = new TextView(this);
        l1.setText("\nআউটপুট ফরম্যাট:");
        root.addView(l1);
        fmtSpinner = new Spinner(this);
        fmtSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"JPG", "PNG", "WebP", "PDF (সব মিলিয়ে একটা)", "DOCX (সব মিলিয়ে একটা)"}));
        root.addView(fmtSpinner);

        TextView l2 = new TextView(this);
        l2.setText("\nসাইজ লিমিট (খালি রাখলে লিমিট নেই):");
        root.addView(l2);
        LinearLayout row = new LinearLayout(this);
        sizeEdit = new EditText(this);
        sizeEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        sizeEdit.setHint("যেমন 20");
        sizeEdit.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        unitSpinner = new Spinner(this);
        unitSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"KB", "MB"}));
        row.addView(sizeEdit);
        row.addView(unitSpinner);
        root.addView(row);

        TextView l3 = new TextView(this);
        l3.setText("\nসাইজ মোড:");
        root.addView(l3);
        modeSpinner = new Spinner(this);
        modeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"ছোট করো (এই সাইজের মধ্যে)", "বড় করো (কমপক্ষে এই সাইজ)", "ঠিক এই সাইজ (ছোট/বড় দুটোই)"}));
        root.addView(modeSpinner);

        TextView note = new TextView(this);
        note.setText("প্রতিটি আউটপুট ফাইল (PDF/DOCX হলে পুরো ফাইল) এই সাইজ ধরে হবে। \"বড় করো\" মোডে ফাইলে অতিরিক্ত ডেটা যোগ হয় — ছবির কোয়ালিটি বাড়ে না।");
        note.setTextSize(12);
        root.addView(note);

        convertBtn = new Button(this);
        convertBtn.setText("কনভার্ট করো");
        convertBtn.setOnClickListener(v -> startConvert());
        root.addView(convertBtn);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(bar);
        status = new TextView(this);
        status.setText("আউটপুট যাবে: Downloads/Converter");
        root.addView(status);

        shareBtn = new Button(this);
        shareBtn.setText("কনভার্ট হওয়া ফাইল শেয়ার করো");
        shareBtn.setVisibility(View.GONE);
        shareBtn.setOnClickListener(v -> shareOutputs());
        root.addView(shareBtn);
        setContentView(sv);
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
            if (u != null) { picked.clear(); picked.add(u); }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if ((l == null || l.isEmpty()) && in.getClipData() != null) {
                l = new ArrayList<>();
                for (int i = 0; i < in.getClipData().getItemCount(); i++) {
                    Uri cu = in.getClipData().getItemAt(i).getUri();
                    if (cu != null) l.add(cu);
                }
            }
            if (l != null && !l.isEmpty()) { picked.clear(); picked.addAll(l); }
        } else return;
        showPicked();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != 1 || res != RESULT_OK || data == null) return;
        picked.clear();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                picked.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) picked.add(data.getData());
        showPicked();
    }

    void showPicked() {
        if (picked.isEmpty()) { listView.setText("কোনো ফাইল বাছাই হয়নি"); return; }
        StringBuilder sb = new StringBuilder(picked.size() + "টি ফাইল বাছাই হয়েছে:");
        for (Uri u : picked) sb.append("\n• ").append(displayName(u));
        listView.setText(sb.toString());
    }

    void say(String s) { runOnUiThread(() -> status.setText(s)); }

    void startConvert() {
        if (picked.isEmpty()) { Toast.makeText(this, "আগে ফাইল বাছাই করো", Toast.LENGTH_SHORT).show(); return; }
        final int fmt = fmtSpinner.getSelectedItemPosition();
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
                Bitmap bm = decode(u);
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
        ImageDecoder.Source s = ImageDecoder.createSource(getContentResolver(), u);
        return ImageDecoder.decodeBitmap(s, (dec, info, src) -> {
            dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            int m = Math.max(w, h);
            if (m > 4096) {
                float f = 4096f / m;
                dec.setTargetSize(Math.round(w * f), Math.round(h * f));
            }
        });
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
