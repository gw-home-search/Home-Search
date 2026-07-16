package com.home.application.ingest.rtms;

import com.home.application.ingest.trade.IngestResult;
import com.home.domain.ingest.run.RtmsMonthlyRefreshRunStatus;

public record RtmsMonthlyRefreshRunSummary(
        String lawdCd,
        String dealYmd,
        long read,
        long rawSaved,
        long normalizedInserted,
        long duplicateSkipped,
        long canceledSkipped,
        long matchFailed,
        long parseFailed,
        int pageCount,
        RtmsMonthlyRefreshRunStatus status,
        String failureReason,
        Long runId) {

    public RtmsMonthlyRefreshRunSummary(
            String lawdCd,
            String dealYmd,
            long read,
            long rawSaved,
            long normalizedInserted,
            long duplicateSkipped,
            long canceledSkipped,
            long matchFailed,
            long parseFailed,
            int pageCount,
            RtmsMonthlyRefreshRunStatus status,
            String failureReason) {
        this(
                lawdCd,
                dealYmd,
                read,
                rawSaved,
                normalizedInserted,
                duplicateSkipped,
                canceledSkipped,
                matchFailed,
                parseFailed,
                pageCount,
                status,
                failureReason,
                null);
    }

    public RtmsMonthlyRefreshRunSummary(
            String lawdCd,
            String dealYmd,
            long read,
            long rawSaved,
            long normalizedInserted,
            long duplicateSkipped,
            long matchFailed,
            long parseFailed,
            int pageCount,
            RtmsMonthlyRefreshRunStatus status,
            String failureReason) {
        this(
                lawdCd,
                dealYmd,
                read,
                rawSaved,
                normalizedInserted,
                duplicateSkipped,
                0,
                matchFailed,
                parseFailed,
                pageCount,
                status,
                failureReason,
                null);
    }

    public static RtmsMonthlyRefreshRunSummary completed(
            String lawdCd, String dealYmd, int pageCount, IngestResult result) {
        return completed(lawdCd, dealYmd, pageCount, result, null);
    }

    public static RtmsMonthlyRefreshRunSummary completed(
            String lawdCd, String dealYmd, int pageCount, IngestResult result, Long runId) {
        return of(lawdCd, dealYmd, pageCount, result, RtmsMonthlyRefreshRunStatus.COMPLETED, null, runId);
    }

    public static RtmsMonthlyRefreshRunSummary failed(
            String lawdCd, String dealYmd, int pageCount, IngestResult result, String failureReason) {
        return failed(lawdCd, dealYmd, pageCount, result, failureReason, null);
    }

    public static RtmsMonthlyRefreshRunSummary failed(
            String lawdCd, String dealYmd, int pageCount, IngestResult result, String failureReason, Long runId) {
        return of(lawdCd, dealYmd, pageCount, result, RtmsMonthlyRefreshRunStatus.FAILED, failureReason, runId);
    }

    public static RtmsMonthlyRefreshRunSummary partiallyFailed(
            String lawdCd, String dealYmd, int pageCount, IngestResult result, String failureReason) {
        return partiallyFailed(lawdCd, dealYmd, pageCount, result, failureReason, null);
    }

    public static RtmsMonthlyRefreshRunSummary partiallyFailed(
            String lawdCd, String dealYmd, int pageCount, IngestResult result, String failureReason, Long runId) {
        return of(lawdCd, dealYmd, pageCount, result, RtmsMonthlyRefreshRunStatus.PARTIAL, failureReason, runId);
    }

    public static RtmsMonthlyRefreshRunSummary of(
            String lawdCd,
            String dealYmd,
            int pageCount,
            IngestResult result,
            RtmsMonthlyRefreshRunStatus status,
            String failureReason,
            Long runId) {
        return new RtmsMonthlyRefreshRunSummary(
                lawdCd,
                dealYmd,
                result.read(),
                result.rawSaved(),
                result.normalizedInserted(),
                result.duplicateSkipped(),
                result.canceledSkipped(),
                result.matchFailed(),
                result.parseFailed(),
                pageCount,
                status,
                status.failureReason(failureReason),
                runId);
    }

    public boolean hasNewData() {
        return normalizedInserted > 0;
    }

    public IngestResult ingestResult() {
        return new IngestResult(
                read, rawSaved, normalizedInserted, duplicateSkipped, canceledSkipped, matchFailed, parseFailed);
    }
}
