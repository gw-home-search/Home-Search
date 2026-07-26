package com.home.application.news.read;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public record MarketNewsCursor(UUID snapshotId, int sortRank) {

    public static MarketNewsCursor decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > 128) {
            throw new InvalidNewsQueryException("cursor가 올바르지 않습니다");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) {
                throw new IllegalArgumentException("cursor shape");
            }
            MarketNewsCursor cursor = new MarketNewsCursor(UUID.fromString(parts[1]), Integer.parseInt(parts[2]));
            if (cursor.sortRank() <= 0) {
                throw new IllegalArgumentException("cursor sort rank");
            }
            return cursor;
        } catch (IllegalArgumentException exception) {
            throw new InvalidNewsQueryException("cursor가 올바르지 않습니다");
        }
    }

    public String encode() {
        String value = "v1:" + snapshotId + ":" + sortRank;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
