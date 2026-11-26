package com.example.converter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** উপরের ট্যাব বার: Converter | পাসপোর্ট ফটো */
final class TabBar {
    private TabBar() { }

    static View make(final Activity a, final int selected) {
        final float d = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setPadding((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x30FFFFFF);
        bg.setCornerRadius(22 * d);
        bg.setStroke((int) d, 0x66FFFFFF);
        row.setBackground(bg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = (int) (14 * d);
        row.setLayoutParams(rlp);

        String[] labels = {"🔄 Converter", "🪪 পাসপোর্ট ফটো"};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            TextView t = new TextView(a);
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(14);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
            if (i == selected) {
                GradientDrawable on = new GradientDrawable();
                on.setColor(0xFFFFD166);
                on.setCornerRadius(18 * d);
                t.setBackground(on);
                t.setTextColor(0xFF1A1446);
            } else {
                t.setTextColor(Color.WHITE);
            }
            t.setOnClickListener(v -> {
                if (idx == selected) return;
                if (selected == 0) a.startActivity(new Intent(a, PassportActivity.class));
                else a.finish();
                a.overridePendingTransition(0, 0);
            });
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return row;
    }
}
