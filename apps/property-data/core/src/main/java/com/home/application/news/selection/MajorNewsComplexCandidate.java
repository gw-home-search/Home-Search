package com.home.application.news.selection;

public record MajorNewsComplexCandidate(
        long complexId,
        String sidoCode,
        String sidoName,
        String sigunguName,
        String dongName,
        String complexName,
        int tradeCount90d,
        Integer unitCount) {}
