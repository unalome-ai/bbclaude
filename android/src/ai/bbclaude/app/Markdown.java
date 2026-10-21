package ai.bbclaude.app;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

/** A small Markdown renderer: headings, lists, quotes, code, bold, italics, links. */
public class Markdown {
    static CharSequence render(String md, float density) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = md.split("\n", -1);
        boolean inCode = false;
        int codeStart = 0;
        int indent = (int) (18 * density);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            boolean last = li == lines.length - 1;
            if (line.trim().startsWith("```")) {
                if (!inCode) {
                    inCode = true;
                    codeStart = sb.length();
                } else {
                    inCode = false;
                    codeSpan(sb, codeStart, sb.length());
                }
                continue;
            }
            if (inCode) {
                sb.append(line);
                if (!last) sb.append('\n');
                continue;
            }
            int start = sb.length();
            String trimmed = line.trim();
            if (trimmed.matches("^#{1,6} .*")) {
                int level = trimmed.indexOf(' ');
                inline(sb, trimmed.substring(level + 1));
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                float size = level == 1 ? 1.35f : level == 2 ? 1.2f : 1.08f;
                sb.setSpan(new RelativeSizeSpan(size), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.matches("^[-*+] .*")) {
                int depth = (line.length() - line.replaceAll("^\\s+", "").length()) / 2;
                sb.append("•  ");
                inline(sb, trimmed.substring(2));
                sb.setSpan(new LeadingMarginSpan.Standard(indent * depth, indent * depth + indent),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.matches("^\\d+[.)] .*")) {
                int depth = (line.length() - line.replaceAll("^\\s+", "").length()) / 2;
                inline(sb, trimmed);
                sb.setSpan(new LeadingMarginSpan.Standard(indent * depth, indent * depth + indent),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith(">")) {
                inline(sb, trimmed.replaceFirst("^>\\s?", ""));
                sb.setSpan(new ForegroundColorSpan(Theme.TEXT_DIM), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new LeadingMarginSpan.Standard(indent), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                sb.append("———");
                sb.setSpan(new ForegroundColorSpan(Theme.TEXT_DIM), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("|")) {
                sb.append(line);
                sb.setSpan(new TypefaceSpan("monospace"), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                inline(sb, line);
            }
            if (!last) sb.append('\n');
        }
        if (inCode) codeSpan(sb, codeStart, sb.length()); // unterminated while streaming
        return sb;
    }

    private static void codeSpan(SpannableStringBuilder sb, int start, int end) {
        if (end <= start) return;
        sb.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Theme.CODE_TEXT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new BackgroundColorSpan(Theme.CODE_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.9f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Inline markup: **bold**, *italic* / _italic_, `code`, [text](url). */
    private static void inline(SpannableStringBuilder sb, String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i) {
                    int st = sb.length();
                    sb.append(s, i + 1, end);
                    codeSpan(sb, st, sb.length());
                    i = end + 1;
                    continue;
                }
            } else if ((c == '*' || c == '_') && i + 1 < s.length() && s.charAt(i + 1) == c) {
                int end = s.indexOf("" + c + c, i + 2);
                if (end > i + 2) {
                    int st = sb.length();
                    inline(sb, s.substring(i + 2, end));
                    sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            } else if ((c == '*' || c == '_') && i + 1 < s.length() && s.charAt(i + 1) != ' '
                    && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                int end = s.indexOf(c, i + 1);
                if (end > i + 1 && s.charAt(end - 1) != ' ') {
                    int st = sb.length();
                    inline(sb, s.substring(i + 1, end));
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            } else if (c == '[') {
                int close = s.indexOf("](", i);
                int paren = close > 0 ? s.indexOf(')', close) : -1;
                if (close > i && paren > close) {
                    int st = sb.length();
                    sb.append(s, i + 1, close);
                    sb.setSpan(new ForegroundColorSpan(Theme.ACCENT), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new UnderlineSpan(), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = paren + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
    }
}
