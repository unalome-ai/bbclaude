package ai.bbclaude.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** BlackBerry 10 dark palette, plus coral for the berry mark. */
public class Theme {
    static final int BG = 0xFF121212;
    static final int BAR = 0xFF1E1E1E;
    static final int SURFACE = 0xFF262626;
    static final int DIVIDER = 0xFF333333;
    static final int USER_BUBBLE = 0xFF2B2B2B;
    static final int TEXT = 0xFFF2F2F2;
    static final int TEXT_DIM = 0xFF9A9A9A;
    static final int ACCENT = 0xFF00A8DF;   // BB10 blue
    static final int CLAUDE = 0xFFD97757;   // Claude coral
    static final int ERROR = 0xFFE5534B;
    static final int CODE_BG = 0xFF1B2A33;
    static final int CODE_TEXT = 0xFFB8E2F2;

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** Small vector icons drawn on a canvas, so no image assets or special glyphs are needed. */
    static class Icon extends View {
        static final int MENU = 0, PLUS = 1, MORE = 2, SEND = 3, STOP = 4, BERRY = 5, BACK = 6, TRASH = 7;
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;

        Icon(Context ctx, int kind, int color) {
            super(ctx);
            this.kind = kind;
            this.color = color;
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setColor(int c) { color = c; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);
            float cx = w / 2, cy = h / 2, u = s / 24f; // 24-unit grid like material icons
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f * u);
            switch (kind) {
                case MENU:
                    for (int i = -1; i <= 1; i++) c.drawLine(cx - 8 * u, cy + i * 6 * u, cx + 8 * u, cy + i * 6 * u, paint);
                    break;
                case PLUS:
                    c.drawLine(cx - 8 * u, cy, cx + 8 * u, cy, paint);
                    c.drawLine(cx, cy - 8 * u, cx, cy + 8 * u, paint);
                    break;
                case MORE:
                    paint.setStyle(Paint.Style.FILL);
                    for (int i = -1; i <= 1; i++) c.drawCircle(cx, cy + i * 6.5f * u, 2 * u, paint);
                    break;
                case SEND:
                    c.drawLine(cx, cy + 7 * u, cx, cy - 7 * u, paint);
                    c.drawLine(cx - 6 * u, cy - 1 * u, cx, cy - 7 * u, paint);
                    c.drawLine(cx + 6 * u, cy - 1 * u, cx, cy - 7 * u, paint);
                    break;
                case STOP:
                    paint.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(new RectF(cx - 5 * u, cy - 5 * u, cx + 5 * u, cy + 5 * u), 1.5f * u, 1.5f * u, paint);
                    break;
                case BACK:
                    c.drawLine(cx - 7 * u, cy, cx + 7 * u, cy, paint);
                    c.drawLine(cx - 7 * u, cy, cx - 1 * u, cy - 6 * u, paint);
                    c.drawLine(cx - 7 * u, cy, cx - 1 * u, cy + 6 * u, paint);
                    break;
                case TRASH:
                    c.drawLine(cx - 7 * u, cy - 6 * u, cx + 7 * u, cy - 6 * u, paint);
                    c.drawLine(cx - 2 * u, cy - 8 * u, cx + 2 * u, cy - 8 * u, paint);
                    c.drawRoundRect(new RectF(cx - 5 * u, cy - 4 * u, cx + 5 * u, cy + 8 * u), u, u, paint);
                    break;
                case BERRY:
                    drawBerry(c, cx, cy, s * 0.46f, paint);
                    break;
            }
        }
    }

    /** The bbclaude mark: a small berry of drupelets (rows of 3, 2, 1) under a blue leaf. */
    static void drawBerry(Canvas c, float cx, float cy, float r, Paint p) {
        int berry = p.getColor();
        float d = r * 0.27f, step = d * 2.1f, top = cy - r * 0.18f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(ACCENT);
        c.drawOval(new RectF(cx - d * 0.2f, top - d * 2.3f, cx + d * 1.6f, top - d * 1.1f), p);
        p.setColor(berry);
        int[] rows = {3, 2, 1};
        for (int row = 0; row < rows.length; row++)
            for (int i = 0; i < rows[row]; i++)
                c.drawCircle(cx + (i - (rows[row] - 1) / 2f) * step, top + row * step * 0.88f, d, p);
    }
}
