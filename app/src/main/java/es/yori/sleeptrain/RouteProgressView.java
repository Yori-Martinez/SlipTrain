package es.yori.sleeptrain;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One current stop, a short lookahead and the final stop or remaining count. */
@android.annotation.SuppressLint("ViewConstructor") // Instantiated by MainActivity with its palette.
final class RouteProgressView extends LinearLayout {
    private final int accent, muted, foreground, track;
    private List<String> shown = Collections.emptyList();
    private boolean vertical, remainingCount;

    RouteProgressView(Context context, int accent, int muted, int foreground, int track) {
        super(context);
        this.accent = accent;
        this.muted = muted;
        this.foreground = foreground;
        this.track = track;
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        setPadding(0, dp(6), 0, dp(6));
    }

    void show(List<String> stops, int current, boolean vertical) {
        int last = stops.size() - 1;
        current = Math.min(last, Math.max(0, current));
        int remaining = last - current;
        boolean count = remaining > 4;
        List<String> visible = new ArrayList<>();
        for (int index = current; index < Math.min(current + 3, last); index++) visible.add(stops.get(index));
        visible.add(count ? remaining + " paradas" : stops.get(last));
        if (visible.equals(shown) && this.vertical == vertical && remainingCount == count) return;
        boolean animate = !shown.isEmpty();
        shown = visible;
        this.vertical = vertical;
        remainingCount = count;
        removeAllViews();
        setOrientation(vertical ? VERTICAL : HORIZONTAL);
        for (int i = 0; i < visible.size(); i++) {
            boolean first = i == 0, end = i == visible.size() - 1;
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(vertical ? HORIZONTAL : VERTICAL);
            cell.setGravity(vertical ? Gravity.CENTER_VERTICAL : Gravity.TOP);
            addView(cell, vertical ? new LayoutParams(-1, -2) : new LayoutParams(0, -2, 1));
            cell.addView(new Dot(getContext(), first, end),
                    vertical ? new LayoutParams(dp(28), dp(36)) : new LayoutParams(-1, dp(26)));
            TextView label = new TextView(getContext());
            label.setText(visible.get(i));
            label.setTextSize(12);
            label.setTextColor(first ? accent : end ? foreground : muted);
            if (first || end) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setMaxLines(!vertical && i == 2 && !end ? 1 : 2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(vertical ? Gravity.START : Gravity.CENTER_HORIZONTAL);
            label.setPadding(dp(3), 0, dp(3), 0);
            label.setContentDescription((first ? "Estación actual · " : end && !count ? "Destino · " : "") + visible.get(i));
            cell.addView(label, new LayoutParams(-1, -2));
        }
        if (animate) ViewMotion.enter(this, 3);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class Dot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean first, last;

        Dot(Context context, boolean first, boolean last) {
            super(context);
            this.first = first;
            this.last = last;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float x = getWidth() / 2f, y = getHeight() / 2f;
            paint.setStrokeWidth(dp(2));
            paint.setColor(first ? accent : track);
            if (vertical) canvas.drawLine(x, 0, x, y, paint);
            else canvas.drawLine(0, y, x, y, paint);
            if (!last) {
                paint.setColor(track);
                if (vertical) canvas.drawLine(x, y, x, getHeight(), paint);
                else canvas.drawLine(x, y, getWidth(), y, paint);
            }
            paint.setColor(first || last ? accent : muted);
            canvas.drawCircle(x, y, dp(first ? 7 : last ? 6 : 4), paint);
            if (last && !first) {
                paint.setColor(track);
                canvas.drawCircle(x, y, dp(3), paint);
            }
        }
    }
}
