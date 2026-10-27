package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the view count out of a card's second line, e.g. "Tom Scott • 2m views • 2 weeks ago".
 */
public class ViewCounts {
    private static final Pattern VIEWS = Pattern.compile("([\\d][\\d.,]*)\\s*([kmb])?\\s+views?\\b", Pattern.CASE_INSENSITIVE);

    /**
     * @return the view count, or -1 when the text has none (or is in another language)
     */
    public static long parse(CharSequence secondTitle) {
        if (secondTitle == null) {
            return -1;
        }

        Matcher matcher = VIEWS.matcher(secondTitle);

        if (!matcher.find()) {
            return -1;
        }

        String suffix = matcher.group(2) != null ? matcher.group(2).toLowerCase(Locale.ROOT) : "";
        // Thousands separators only appear in plain numbers ("1,234 views"); with a suffix the dot is decimal
        String number = suffix.isEmpty() ? matcher.group(1).replace(",", "").replace(".", "") : matcher.group(1).replace(",", ".");

        try {
            double value = Double.parseDouble(number);

            switch (suffix) {
                case "k":
                    value *= 1_000;
                    break;
                case "m":
                    value *= 1_000_000;
                    break;
                case "b":
                    value *= 1_000_000_000;
                    break;
            }

            return (long) value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
