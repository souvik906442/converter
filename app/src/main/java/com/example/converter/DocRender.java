package com.example.converter;

import android.graphics.*;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

/** Simple offline DOCX / XLSX -> page bitmaps renderer (text, headings, bold/italic, tables, images). */
public class DocRender {
    public interface PageSink { void page(Bitmap b) throws Exception; }

    static final int W = 1190, H = 1684, M = 100, AVAIL = W - 2 * M;
    static final float BASE = 22f;
    static final float[] HM = {1f, 1.9f, 1.6f, 1.35f, 1.15f, 1.1f};

    static class Run {
        String t; boolean b, i; float sz;
        Run(String t, boolean b, boolean i, float sz) { this.t = t; this.b = b; this.i = i; this.sz = sz; }
    }

    static class Tok {
        String s; boolean b, i, nl, tab, glue; float sz, w, gap;
    }

    /** Returns page count. If dry, nothing is drawn (only counts pages). */
    public static int render(Map<String, byte[]> z, boolean xlsx, PageSink sink, boolean dry) throws Exception {
        Layout L = new Layout(sink, dry);
        if (xlsx) renderXlsx(z, L); else renderDocx(z, L);
        L.flush();
        return L.pages;
    }

    static class Layout {
        PageSink sink; boolean dry; Bitmap cur; Canvas cv; float y; boolean started; int pages;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint line = new Paint();
        Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        Layout(PageSink s, boolean dry) {
            sink = s; this.dry = dry;
            p.setColor(Color.BLACK);
            line.setStyle(Paint.Style.STROKE);
            line.setColor(0xFF888888);
            line.setStrokeWidth(1.5f);
        }

        void newPage() {
            if (!dry) { cur = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); cur.eraseColor(Color.WHITE); cv = new Canvas(cur); }
            y = M; started = true;
        }

        void flush() throws Exception {
            if (started) {
                pages++; started = false;
                if (!dry) { Bitmap b = cur; cur = null; cv = null; sink.page(b); }
            }
        }

        void ensure(float h) throws Exception {
            if (!started) newPage();
            else if (y + h > H - M) { flush(); newPage(); }
        }

        void style(Tok t) {
            p.setTextSize(t.sz);
            int st = (t.b ? Typeface.BOLD : 0) | (t.i ? Typeface.ITALIC : 0);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, st));
        }

        float measure(Tok t) { style(t); return p.measureText(t.s); }
        float spaceW(Tok t) { style(t); return p.measureText(" "); }

        void addWord(List<Tok> toks, String w, Run r, float avail, boolean glue) {
            Tok t = new Tok();
            t.b = r.b; t.i = r.i; t.sz = r.sz > 0 ? r.sz : BASE;
            t.s = w; t.glue = glue;
            style(t);
            if (p.measureText(w) <= avail) { t.w = p.measureText(w); toks.add(t); return; }
            String rest = w; boolean first = true;
            while (!rest.isEmpty()) {
                int n = p.breakText(rest, true, avail, null);
                if (n <= 0) n = 1;
                Tok c = new Tok();
                c.b = t.b; c.i = t.i; c.sz = t.sz; c.s = rest.substring(0, n);
                c.glue = first && glue;
                c.w = p.measureText(c.s);
                toks.add(c);
                rest = rest.substring(n); first = false;
            }
        }

        List<List<Tok>> wrap(List<Run> runs, float avail) {
            if (avail < 20) avail = 20;
            List<Tok> toks = new ArrayList<>();
            boolean prevNonSpace = false;
            for (Run r : runs) {
                if (r.t == null) continue;
                if (r.t.equals("\n")) {
                    Tok t = new Tok(); t.nl = true; t.sz = r.sz > 0 ? r.sz : BASE; toks.add(t);
                    prevNonSpace = false; continue;
                }
                if (r.t.equals("\t")) {
                    Tok t = new Tok(); t.tab = true; t.s = ""; t.w = 60; t.sz = r.sz > 0 ? r.sz : BASE; toks.add(t);
                    prevNonSpace = false; continue;
                }
                String text = r.t;
                if (text.isEmpty()) continue;
                boolean startsSpace = Character.isWhitespace(text.charAt(0));
                boolean endsSpace = Character.isWhitespace(text.charAt(text.length() - 1));
                boolean firstWord = true;
                for (String w : text.split("\\s+")) {
                    if (w.isEmpty()) continue;
                    boolean glue = firstWord && prevNonSpace && !startsSpace;
                    addWord(toks, w, r, avail, glue);
                    firstWord = false;
                }
                prevNonSpace = !endsSpace;
            }
            List<List<Tok>> lines = new ArrayList<>();
            List<Tok> cur = new ArrayList<>();
            float curW = 0;
            for (Tok t : toks) {
                if (t.nl) { lines.add(cur); cur = new ArrayList<>(); curW = 0; continue; }
                float gap = (cur.isEmpty() || t.glue) ? 0 : spaceW(t);
                if (!cur.isEmpty() && !t.glue && curW + gap + t.w > avail) {
                    lines.add(cur); cur = new ArrayList<>(); curW = 0; gap = 0;
                }
                t.gap = gap;
                cur.add(t);
                curW += gap + t.w;
            }
            lines.add(cur);
            return lines;
        }

        float maxSz(List<Tok> l) {
            float m = 0;
            for (Tok t : l) m = Math.max(m, t.sz);
            return m == 0 ? BASE : m;
        }

        float lineHeight(List<Tok> l) { return maxSz(l) * 1.4f; }

        float lineWidth(List<Tok> l) {
            float w = 0;
            for (Tok t : l) w += t.gap + t.w;
            return w;
        }

        void drawLine(List<Tok> l, float x0, float yTop, float width, int align) {
            if (dry) return;
            float lw = lineWidth(l);
            float x = align == 1 ? x0 + (width - lw) / 2 : align == 2 ? x0 + width - lw : x0;
            float m = maxSz(l);
            for (Tok t : l) {
                x += t.gap;
                if (!t.tab && t.s != null) { style(t); cv.drawText(t.s, x, yTop + m * 1.05f, p); }
                x += t.w;
            }
        }

        void paragraph(List<Run> runs, int align, float after) throws Exception {
            List<List<Tok>> lines = wrap(runs, AVAIL);
            for (List<Tok> l : lines) {
                float h = lineHeight(l);
                ensure(h);
                drawLine(l, M, y, AVAIL, align);
                y += h;
            }
            y += after;
        }

        void gap(float g) { if (started) y += g; }

        void image(byte[] data) throws Exception {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return;
            float w = Math.min(AVAIL, o.outWidth * 1.5f);
            float h = w * o.outHeight / o.outWidth;
            float maxH = H - 2 * M;
            if (h > maxH) { h = maxH; w = h * o.outWidth / o.outHeight; }
            ensure(h + 10);
            if (!dry) {
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                int s = 1;
                while (o.outWidth / (s * 2) >= w) s *= 2;
                o2.inSampleSize = s;
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
                if (b != null) { cv.drawBitmap(b, null, new RectF(M, y, M + w, y + h), bmpPaint); b.recycle(); }
            }
            y += h + 10;
        }

        void tableRow(List<List<Run>> cells, float[] colW, float pad) throws Exception {
            int n = cells.size();
            if (n == 0) return;
            float[] cw;
            if (colW != null && colW.length == n) cw = colW;
            else { cw = new float[n]; Arrays.fill(cw, AVAIL / (float) n); }
            List<List<List<Tok>>> all = new ArrayList<>();
            float rowH = 0;
            for (int i = 0; i < n; i++) {
                List<List<Tok>> ls = wrap(cells.get(i), cw[i] - 2 * pad);
                if (ls.size() > 40) ls = new ArrayList<>(ls.subList(0, 40));
                all.add(ls);
                float h = 2 * pad;
                for (List<Tok> l : ls) h += lineHeight(l);
                rowH = Math.max(rowH, h);
            }
            ensure(rowH);
            if (!dry) {
                float x = M;
                for (int i = 0; i < n; i++) {
                    cv.drawRect(x, y, x + cw[i], y + rowH, line);
                    float yy = y + pad;
                    for (List<Tok> l : all.get(i)) {
                        drawLine(l, x + pad, yy, cw[i] - 2 * pad, 0);
                        yy += lineHeight(l);
                    }
                    x += cw[i];
                }
            }
            y += rowH;
        }
    }

    // ---------------- DOCX ----------------

    static Map<String, String> parseRels(byte[] data) throws Exception {
        Map<String, String> m = new HashMap<>();
        if (data == null) return m;
        XmlPullParser xp = Xml.newPullParser();
        xp.setInput(new ByteArrayInputStream(data), "UTF-8");
        for (int ev = xp.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = xp.next()) {
            if (ev == XmlPullParser.START_TAG && xp.getName().equals("Relationship")) {
                String id = xp.getAttributeValue(null, "Id");
                String tg = xp.getAttributeValue(null, "Target");
                if (id != null && tg != null) m.put(id, tg);
            }
        }
        return m;
    }

    static boolean off(String v) { return v != null && (v.equals("0") || v.equals("false")); }

    static void renderDocx(Map<String, byte[]> z, Layout L) throws Exception {
        byte[] doc = z.get("word/document.xml");
        if (doc == null) throw new IOException("এটা সঠিক DOCX ফাইল না");
        Map<String, String> rels = parseRels(z.get("word/_rels/document.xml.rels"));
        XmlPullParser xp = Xml.newPullParser();
        xp.setInput(new ByteArrayInputStream(doc), "UTF-8");

        List<Run> runs = null;
        List<byte[]> imgs = new ArrayList<>();
        int align = 0, head = 0;
        boolean inPPr = false, inRPr = false, inT = false, listItem = false, pageBreak = false;
        boolean rb = false, ri = false;
        float rsz = 0;
        int tblDepth = 0;
        List<Float> grid = new ArrayList<>();
        List<List<Run>> rowCells = null;
        List<Run> cellRuns = null;

        for (int ev = xp.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = xp.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = xp.getName();
                switch (n) {
                    case "w:p":
                        runs = new ArrayList<>(); imgs = new ArrayList<>();
                        align = 0; head = 0; listItem = false; pageBreak = false;
                        break;
                    case "w:pPr": inPPr = true; break;
                    case "w:rPr": inRPr = true; break;
                    case "w:pStyle": {
                        String v = xp.getAttributeValue(null, "w:val");
                        if (v != null) {
                            String s = v.toLowerCase(Locale.ROOT);
                            if (s.contains("title")) head = 1;
                            else if (s.contains("heading1") || s.equals("1")) head = 2;
                            else if (s.contains("heading2") || s.equals("2")) head = 3;
                            else if (s.contains("heading3") || s.equals("3")) head = 4;
                            else if (s.contains("heading")) head = 5;
                        }
                        break;
                    }
                    case "w:jc":
                        if (inPPr) {
                            String v = xp.getAttributeValue(null, "w:val");
                            if ("center".equals(v)) align = 1;
                            else if ("right".equals(v) || "end".equals(v)) align = 2;
                            else align = 0;
                        }
                        break;
                    case "w:numPr": if (inPPr) listItem = true; break;
                    case "w:r": rb = false; ri = false; rsz = 0; break;
                    case "w:b": if (inRPr && !inPPr) rb = !off(xp.getAttributeValue(null, "w:val")); break;
                    case "w:i": if (inRPr && !inPPr) ri = !off(xp.getAttributeValue(null, "w:val")); break;
                    case "w:sz":
                        if (inRPr && !inPPr) {
                            try { rsz = Float.parseFloat(xp.getAttributeValue(null, "w:val")); } catch (Exception e) { }
                        }
                        break;
                    case "w:t": inT = true; break;
                    case "w:tab":
                        if (!inPPr && runs != null) runs.add(new Run("\t", false, false, 0));
                        break;
                    case "w:br":
                        if (runs != null) {
                            if ("page".equals(xp.getAttributeValue(null, "w:type"))) pageBreak = true;
                            else runs.add(new Run("\n", false, false, 0));
                        }
                        break;
                    case "a:blip": {
                        String id = xp.getAttributeValue(null, "r:embed");
                        String tg = id == null ? null : rels.get(id);
                        if (tg != null) {
                            String path = tg.startsWith("/") ? tg.substring(1) : "word/" + tg;
                            byte[] d = z.get(path);
                            if (d != null) imgs.add(d);
                        }
                        break;
                    }
                    case "w:tbl":
                        tblDepth++;
                        if (tblDepth == 1) grid = new ArrayList<>();
                        break;
                    case "w:gridCol":
                        if (tblDepth == 1) {
                            try { grid.add(Float.parseFloat(xp.getAttributeValue(null, "w:w"))); } catch (Exception e) { }
                        }
                        break;
                    case "w:tr": if (tblDepth == 1) rowCells = new ArrayList<>(); break;
                    case "w:tc": if (tblDepth == 1) cellRuns = new ArrayList<>(); break;
                    default: break;
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (inT && runs != null) {
                    float sz = rsz > 0 ? rsz : (head > 0 ? BASE * HM[head] : BASE);
                    runs.add(new Run(xp.getText(), rb || head > 0, ri, sz));
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String n = xp.getName();
                switch (n) {
                    case "w:t": inT = false; break;
                    case "w:pPr": inPPr = false; break;
                    case "w:rPr": inRPr = false; break;
                    case "w:p":
                        if (runs != null) {
                            if (listItem) runs.add(0, new Run("•  ", false, false, 0));
                            if (tblDepth > 0) {
                                if (cellRuns != null) {
                                    if (!cellRuns.isEmpty()) cellRuns.add(new Run("\n", false, false, 0));
                                    cellRuns.addAll(runs);
                                }
                            } else {
                                L.paragraph(runs, align, head > 0 ? 14 : 10);
                                for (byte[] img : imgs) L.image(img);
                                if (pageBreak) L.flush();
                            }
                        }
                        runs = null;
                        break;
                    case "w:tc":
                        if (tblDepth == 1 && rowCells != null && cellRuns != null) rowCells.add(cellRuns);
                        if (tblDepth == 1) cellRuns = null;
                        break;
                    case "w:tr":
                        if (tblDepth == 1 && rowCells != null) {
                            float[] cw = null;
                            if (!grid.isEmpty() && grid.size() == rowCells.size()) {
                                float sum = 0;
                                for (float g : grid) sum += g;
                                cw = new float[grid.size()];
                                for (int i = 0; i < cw.length; i++) cw[i] = grid.get(i) * AVAIL / sum;
                            }
                            L.tableRow(rowCells, cw, 8);
                            rowCells = null;
                        }
                        break;
                    case "w:tbl":
                        tblDepth--;
                        if (tblDepth == 0) L.gap(12);
                        break;
                    default: break;
                }
            }
        }
    }

    // ---------------- XLSX ----------------

    static List<String> parseShared(byte[] data) throws Exception {
        List<String> list = new ArrayList<>();
        if (data == null) return list;
        XmlPullParser xp = Xml.newPullParser();
        xp.setInput(new ByteArrayInputStream(data), "UTF-8");
        StringBuilder sb = null;
        boolean inT = false, inPh = false;
        for (int ev = xp.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = xp.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = xp.getName();
                if (n.equals("si")) sb = new StringBuilder();
                else if (n.equals("rPh")) inPh = true;
                else if (n.equals("t")) inT = true;
            } else if (ev == XmlPullParser.TEXT) {
                if (inT && !inPh && sb != null) sb.append(xp.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                String n = xp.getName();
                if (n.equals("t")) inT = false;
                else if (n.equals("rPh")) inPh = false;
                else if (n.equals("si")) { list.add(sb == null ? "" : sb.toString()); sb = null; }
            }
        }
        return list;
    }

    static int colIndex(String ref) {
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') n = n * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') n = n * 26 + (c - 'a' + 1);
            else break;
        }
        return Math.max(0, n - 1);
    }

    static String num(String raw) {
        try {
            BigDecimal bd = new BigDecimal(raw.trim()).round(new MathContext(12)).stripTrailingZeros();
            return bd.toPlainString();
        } catch (Exception e) { return raw; }
    }

    static List<String[]> parseSheet(byte[] data, List<String> ss) throws Exception {
        List<String[]> rows = new ArrayList<>();
        XmlPullParser xp = Xml.newPullParser();
        xp.setInput(new ByteArrayInputStream(data), "UTF-8");
        List<String> cur = null;
        int col = 0;
        String type = null;
        StringBuilder sb = new StringBuilder();
        boolean inV = false, inT = false, inIs = false;
        for (int ev = xp.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = xp.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = xp.getName();
                switch (n) {
                    case "row": cur = new ArrayList<>(); break;
                    case "c": {
                        String ref = xp.getAttributeValue(null, "r");
                        col = ref != null ? colIndex(ref) : (cur == null ? 0 : cur.size());
                        type = xp.getAttributeValue(null, "t");
                        sb.setLength(0);
                        break;
                    }
                    case "v": inV = true; break;
                    case "is": inIs = true; break;
                    case "t": if (inIs) inT = true; break;
                    default: break;
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (inV || inT) sb.append(xp.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                String n = xp.getName();
                switch (n) {
                    case "v": inV = false; break;
                    case "t": inT = false; break;
                    case "is": inIs = false; break;
                    case "c": {
                        if (cur == null) break;
                        String val = sb.toString();
                        if ("s".equals(type)) {
                            try { val = ss.get(Integer.parseInt(val.trim())); } catch (Exception e) { val = ""; }
                        } else if ("b".equals(type)) {
                            val = val.trim().equals("1") ? "TRUE" : "FALSE";
                        } else if (type == null || "n".equals(type)) {
                            if (!val.isEmpty()) val = num(val);
                        }
                        while (cur.size() < col) cur.add("");
                        if (cur.size() == col) cur.add(val); else cur.set(col, val);
                        break;
                    }
                    case "row":
                        if (cur != null) rows.add(cur.toArray(new String[0]));
                        cur = null;
                        break;
                    default: break;
                }
            }
        }
        return rows;
    }

    static void renderXlsx(Map<String, byte[]> z, Layout L) throws Exception {
        List<String> ss = parseShared(z.get("xl/sharedStrings.xml"));
        List<String> names = new ArrayList<>();
        byte[] wb = z.get("xl/workbook.xml");
        if (wb != null) {
            XmlPullParser xp = Xml.newPullParser();
            xp.setInput(new ByteArrayInputStream(wb), "UTF-8");
            for (int ev = xp.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = xp.next()) {
                if (ev == XmlPullParser.START_TAG && xp.getName().equals("sheet")) {
                    String nm = xp.getAttributeValue(null, "name");
                    names.add(nm == null ? "" : nm);
                }
            }
        }
        final java.util.regex.Pattern pat = java.util.regex.Pattern.compile("xl/worksheets/sheet(\\d+)\\.xml");
        List<String> keys = new ArrayList<>();
        for (String k : z.keySet()) if (pat.matcher(k).matches()) keys.add(k);
        Collections.sort(keys, (a, b) -> {
            java.util.regex.Matcher ma = pat.matcher(a), mb = pat.matcher(b);
            ma.matches(); mb.matches();
            return Integer.compare(Integer.parseInt(ma.group(1)), Integer.parseInt(mb.group(1)));
        });
        if (keys.isEmpty()) throw new IOException("এটা সঠিক XLSX ফাইল না");
        for (int idx = 0; idx < keys.size(); idx++) {
            List<String[]> rows = parseSheet(z.get(keys.get(idx)), ss);
            String name = idx < names.size() ? names.get(idx) : "Sheet" + (idx + 1);
            drawSheet(L, name, rows);
        }
    }

    static void drawSheet(Layout L, String name, List<String[]> rows) throws Exception {
        L.flush();
        List<Run> title = new ArrayList<>();
        title.add(new Run(name, true, false, BASE * 1.4f));
        L.paragraph(title, 0, 10);
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        cols = Math.min(cols, 30);
        if (cols == 0) {
            List<Run> e = new ArrayList<>();
            e.add(new Run("(খালি শীট)", false, true, 0));
            L.paragraph(e, 0, 10);
            return;
        }
        float[] nat = new float[cols];
        L.p.setTextSize(BASE);
        L.p.setTypeface(Typeface.DEFAULT);
        for (String[] r : rows) {
            for (int c = 0; c < cols && c < r.length; c++) {
                if (r[c] == null || r[c].isEmpty()) continue;
                float w = L.p.measureText(r[c]) + 16;
                if (w > nat[c]) nat[c] = w;
            }
        }
        float total = 0;
        for (int c = 0; c < cols; c++) {
            nat[c] = Math.max(60f, Math.min(360f, nat[c]));
            total += nat[c];
        }
        float fs = BASE;
        float[] cw = nat;
        if (total > AVAIL) {
            float scale = AVAIL / total;
            cw = new float[cols];
            for (int c = 0; c < cols; c++) cw[c] = Math.max(24f, nat[c] * scale);
            fs = Math.max(12f, BASE * Math.max(0.55f, scale));
        }
        for (int ri = 0; ri < rows.size(); ri++) {
            String[] r = rows.get(ri);
            List<List<Run>> cells = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                String v = c < r.length && r[c] != null ? r[c] : "";
                List<Run> one = new ArrayList<>();
                one.add(new Run(v, ri == 0 && rows.size() > 1, false, fs));
                cells.add(one);
            }
            L.tableRow(cells, cw, 6);
        }
    }
}
