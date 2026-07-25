package com.home.infrastructure.external.news;

import com.home.application.news.collection.NewsItemNormalizationGateway;
import com.home.application.news.collection.NewsNormalizationResult;
import com.home.application.news.collection.NewsProviderItem;
import com.home.application.news.collection.NormalizedNewsItem;
import com.home.domain.news.NewsRejectionReason;
import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NaverNewsItemNormalizer implements NewsItemNormalizationGateway {

    private static final Pattern MARKUP = Pattern.compile("(?is)<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[A-Za-z]+);");

    @Override
    public NewsNormalizationResult tryNormalize(NewsProviderItem raw) {
        try {
            return NewsNormalizationResult.accepted(normalize(raw));
        } catch (RejectedNewsItemException exception) {
            return NewsNormalizationResult.rejected(exception.reason());
        }
    }

    public NormalizedNewsItem normalize(NewsProviderItem raw) {
        if (raw == null) {
            throw rejected(NewsRejectionReason.MISSING_REQUIRED_FIELD, "raw item is required");
        }
        String title = clean(raw.title());
        String description = clean(raw.description());
        if (title.isBlank() || title.length() > 500) {
            throw rejected(NewsRejectionReason.MISSING_REQUIRED_FIELD, "title is invalid");
        }
        if (raw.pubDate() == null || raw.pubDate().isBlank()) {
            throw rejected(NewsRejectionReason.INVALID_PROVIDED_AT, "pubDate is required");
        }
        URI publicUrl = validPublicUrl(firstNonBlank(raw.originalLink(), raw.link()));
        if (publicUrl.toString().length() > 4000) {
            throw rejected(NewsRejectionReason.INVALID_URL, "public URL is too long");
        }
        URI canonicalUrl = canonicalize(publicUrl);
        try {
            return new NormalizedNewsItem(
                    title,
                    description,
                    firstNonBlank(raw.originalLink(), raw.link()).trim(),
                    sha256(canonicalUrl.toASCIIString()),
                    ZonedDateTime.parse(raw.pubDate(), DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant(),
                    raw.providerStart(),
                    raw.providerRank());
        } catch (DateTimeParseException exception) {
            throw new RejectedNewsItemException(NewsRejectionReason.INVALID_PROVIDED_AT, "invalid pubDate", exception);
        }
    }

    static String clean(String value) {
        if (value == null) {
            return "";
        }
        Matcher matcher = ENTITY.matcher(value);
        StringBuilder decoded = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(decodeEntity(matcher.group(1))));
        }
        matcher.appendTail(decoded);
        return MARKUP.matcher(decoded).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    private static String decodeEntity(String entity) {
        return switch (entity.toLowerCase(Locale.ROOT)) {
            case "amp" -> "&";
            case "quot" -> "\"";
            case "apos", "#39" -> "'";
            case "lt" -> "<";
            case "gt" -> ">";
            case "nbsp" -> " ";
            default -> numericEntity(entity);
        };
    }

    private static String numericEntity(String entity) {
        try {
            int radix = entity.regionMatches(true, 0, "#x", 0, 2) ? 16 : 10;
            String digits = radix == 16 ? entity.substring(2) : entity.substring(1);
            if (!entity.startsWith("#") || digits.isBlank()) {
                return "&" + entity + ";";
            }
            int codePoint = Integer.parseInt(digits, radix);
            if (!Character.isValidCodePoint(codePoint)) {
                return "\uFFFD";
            }
            return new String(Character.toChars(codePoint));
        } catch (IllegalArgumentException exception) {
            return "\uFFFD";
        }
    }

    private static URI validPublicUrl(String value) {
        if (value == null || value.isBlank()) {
            throw rejected(NewsRejectionReason.INVALID_URL, "public URL is required");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new RejectedNewsItemException(NewsRejectionReason.INVALID_URL, "invalid public URL", exception);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                || host == null
                || host.isBlank()
                || uri.getUserInfo() != null) {
            throw rejected(NewsRejectionReason.INVALID_URL, "unsafe public URL");
        }
        try {
            IDN.toASCII(host);
        } catch (IllegalArgumentException exception) {
            throw new RejectedNewsItemException(NewsRejectionReason.INVALID_URL, "invalid public URL host", exception);
        }
        return uri;
    }

    private static URI canonicalize(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        } else if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        try {
            return new URI(scheme, null, host, port, path, uri.getRawQuery(), null);
        } catch (Exception exception) {
            throw new RejectedNewsItemException(NewsRejectionReason.INVALID_URL, "canonical URL failed", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static RejectedNewsItemException rejected(NewsRejectionReason reason, String message) {
        return new RejectedNewsItemException(reason, message, null);
    }

    private static final class RejectedNewsItemException extends IllegalArgumentException {
        private final NewsRejectionReason reason;

        private RejectedNewsItemException(NewsRejectionReason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        private NewsRejectionReason reason() {
            return reason;
        }
    }
}
