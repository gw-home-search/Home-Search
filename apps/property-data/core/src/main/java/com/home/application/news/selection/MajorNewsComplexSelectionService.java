package com.home.application.news.selection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MajorNewsComplexSelectionService {

    private static final int SIDO_MINIMUM = 5;
    private static final int TOTAL = 200;
    private final MajorNewsComplexSelectionRepository repository;

    public MajorNewsComplexSelectionService(MajorNewsComplexSelectionRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public List<MajorNewsComplexCandidate> select(LocalDate asOfDate) {
        LocalDate selectionWeek = asOfDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (repository.hasPublishedSelection(selectionWeek)) {
            return List.of();
        }
        Map<String, List<MajorNewsComplexCandidate>> bySido = repository.findCandidates(asOfDate).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        MajorNewsComplexCandidate::sidoCode, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        if (bySido.size() != 17 || bySido.values().stream().anyMatch(values -> values.size() < SIDO_MINIMUM)) {
            throw new IllegalStateException("17개 시도별 최소 5개 주요 단지 후보가 필요합니다");
        }
        int remaining = TOTAL - (bySido.size() * SIDO_MINIMUM);
        long allTrades = bySido.values().stream()
                .flatMap(List::stream)
                .mapToLong(MajorNewsComplexCandidate::tradeCount90d)
                .sum();
        List<Allocation> allocations = new ArrayList<>();
        int allocatedFloor = 0;
        for (var entry : bySido.entrySet()) {
            long sidoTrades = entry.getValue().stream()
                    .mapToLong(MajorNewsComplexCandidate::tradeCount90d)
                    .sum();
            BigDecimal quota = BigDecimal.valueOf(remaining)
                    .multiply(BigDecimal.valueOf(sidoTrades))
                    .divide(BigDecimal.valueOf(allTrades), 12, RoundingMode.HALF_UP);
            int floor = quota.setScale(0, RoundingMode.DOWN).intValueExact();
            allocatedFloor += floor;
            allocations.add(new Allocation(entry.getKey(), floor, quota.subtract(BigDecimal.valueOf(floor))));
        }
        int leftovers = remaining - allocatedFloor;
        allocations.sort(Comparator.comparing(Allocation::remainder).reversed().thenComparing(Allocation::sidoCode));
        Map<String, Integer> extraBySido = new LinkedHashMap<>();
        for (int index = 0; index < allocations.size(); index++) {
            Allocation allocation = allocations.get(index);
            extraBySido.put(allocation.sidoCode(), allocation.floor() + (index < leftovers ? 1 : 0));
        }
        List<MajorNewsComplexCandidate> selected = new ArrayList<>(TOTAL);
        for (var entry : bySido.entrySet()) {
            int allocation = SIDO_MINIMUM + extraBySido.getOrDefault(entry.getKey(), 0);
            selected.addAll(entry.getValue().stream().limit(allocation).toList());
        }
        selected.sort(Comparator.comparing(MajorNewsComplexCandidate::sidoCode)
                .thenComparing(Comparator.comparingInt(MajorNewsComplexCandidate::tradeCount90d)
                        .reversed())
                .thenComparing(
                        candidate -> candidate.unitCount() == null ? Integer.MIN_VALUE : candidate.unitCount(),
                        Comparator.reverseOrder())
                .thenComparingLong(MajorNewsComplexCandidate::complexId));
        if (selected.size() != TOTAL) {
            throw new IllegalStateException("주요 단지 selection 결과가 200개가 아닙니다");
        }
        repository.publish(selectionWeek, selected);
        return List.copyOf(selected);
    }

    private record Allocation(String sidoCode, int floor, BigDecimal remainder) {}
}
