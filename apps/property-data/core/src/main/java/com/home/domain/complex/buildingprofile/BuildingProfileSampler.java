package com.home.domain.complex.buildingprofile;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class BuildingProfileSampler {
    public BuildingProfileSampleSelection selectAll(
            List<BuildingProfileSampleCandidate> candidates, String selectionSeed) {
        if (selectionSeed == null || selectionSeed.isBlank()) {
            throw new IllegalArgumentException("selectionSeed is required");
        }
        Map<String, BuildingProfileSampleCandidate> unique = new LinkedHashMap<>();
        for (BuildingProfileSampleCandidate candidate : candidates) {
            if (unique.putIfAbsent(candidate.pnu(), candidate) != null) {
                throw new IllegalArgumentException("duplicate PNU candidate");
            }
        }
        if (unique.isEmpty()) throw new IllegalArgumentException("population is empty");
        BuildingProfileSampleStratum stratum = BuildingProfileSampleStratum.NATIONWIDE_CENSUS;
        List<BuildingProfileSampleEntry> entries = unique.values().stream()
                .map(candidate -> new BuildingProfileSampleEntry(
                        candidate.pnu(), stratum, rank(selectionSeed, stratum, candidate.pnu()), 1.0d,
                        candidate.complexCount()))
                .sorted(Comparator.comparingLong(BuildingProfileSampleEntry::seedRank)
                        .thenComparing(BuildingProfileSampleEntry::pnu))
                .toList();
        return new BuildingProfileSampleSelection(
                selectionSeed,
                entries,
                List.of(new BuildingProfileStratumStats(stratum, entries.size(), entries.size(), 1.0d)));
    }

    public BuildingProfileSampleSelection select(
            List<BuildingProfileSampleCandidate> candidates, int sampleSize, String selectionSeed) {
        if (selectionSeed == null || selectionSeed.isBlank())
            throw new IllegalArgumentException("selectionSeed is required");
        if (sampleSize <= 0) throw new IllegalArgumentException("sampleSize must be positive");
        Map<String, BuildingProfileSampleCandidate> unique = new LinkedHashMap<>();
        for (BuildingProfileSampleCandidate candidate : candidates) {
            if (unique.putIfAbsent(candidate.pnu(), candidate) != null) {
                throw new IllegalArgumentException("duplicate PNU candidate");
            }
        }
        if (unique.size() < sampleSize) throw new IllegalArgumentException("population is smaller than sampleSize");

        List<BuildingProfileSampleCandidate> remaining = new ArrayList<>(unique.values());
        List<Selected> selected = new ArrayList<>();
        EnumMap<BuildingProfileSampleStratum, Integer> populations = new EnumMap<>(BuildingProfileSampleStratum.class);

        takeAll(
                remaining,
                selected,
                populations,
                BuildingProfileSampleStratum.SHARED_PNU,
                c -> c.complexCount() > 1,
                selectionSeed);
        requireCapacity(selected, sampleSize);
        takeLegalTransitions(remaining, selected, populations, selectionSeed);
        takeBounded(
                remaining,
                selected,
                populations,
                BuildingProfileSampleStratum.HIERARCHY_RISK,
                BuildingProfileSampleCandidate::hierarchyRisk,
                59,
                selectionSeed);

        List<BuildingProfileSampleCandidate> complexityPopulation = remaining.stream()
                .sorted(Comparator.comparingInt(BuildingProfileSampleCandidate::observedTitleCount)
                        .reversed()
                        .thenComparing(BuildingProfileSampleCandidate::pnu))
                .limit(Math.max(1, (int) Math.ceil(remaining.size() * 0.10d)))
                .toList();
        takePopulation(
                remaining,
                selected,
                populations,
                BuildingProfileSampleStratum.HIGH_COMPLEXITY,
                complexityPopulation,
                100,
                selectionSeed);
        takeBounded(
                remaining,
                selected,
                populations,
                BuildingProfileSampleStratum.METADATA_CONTROL,
                BuildingProfileSampleCandidate::metadataControl,
                50,
                selectionSeed);

        int needed = sampleSize - selected.size();
        if (needed < 0) throw new IllegalArgumentException("priority strata exceed requested sampleSize");
        populations.put(BuildingProfileSampleStratum.REGIONAL_PROPORTIONAL, remaining.size());
        takeRegional(remaining, selected, needed, selectionSeed);
        if (selected.size() != sampleSize) throw new IllegalStateException("could not select exact sampleSize");

        EnumMap<BuildingProfileSampleStratum, Integer> sampleCounts = new EnumMap<>(BuildingProfileSampleStratum.class);
        selected.forEach(value -> sampleCounts.merge(value.stratum(), 1, Integer::sum));
        List<BuildingProfileStratumStats> stats = new ArrayList<>();
        for (BuildingProfileSampleStratum stratum : BuildingProfileSampleStratum.values()) {
            int population = populations.getOrDefault(stratum, 0);
            int sampled = sampleCounts.getOrDefault(stratum, 0);
            if (population == 0 && sampled == 0) continue;
            stats.add(new BuildingProfileStratumStats(
                    stratum, population, sampled, sampled == 0 ? 1.0d : (double) population / sampled));
        }
        Map<BuildingProfileSampleStratum, Double> weights = new EnumMap<>(BuildingProfileSampleStratum.class);
        stats.forEach(stat -> weights.put(stat.stratum(), stat.samplingWeight()));
        List<BuildingProfileSampleEntry> entries = selected.stream()
                .sorted(Comparator.comparing(Selected::stratum).thenComparingLong(Selected::rank))
                .map(value -> new BuildingProfileSampleEntry(
                        value.candidate().pnu(),
                        value.stratum(),
                        value.rank(),
                        weights.get(value.stratum()),
                        value.candidate().complexCount()))
                .toList();
        return new BuildingProfileSampleSelection(selectionSeed, entries, stats);
    }

    private void takeAll(
            List<BuildingProfileSampleCandidate> remaining,
            List<Selected> selected,
            Map<BuildingProfileSampleStratum, Integer> populations,
            BuildingProfileSampleStratum stratum,
            Predicate<BuildingProfileSampleCandidate> predicate,
            String seed) {
        takeBounded(remaining, selected, populations, stratum, predicate, Integer.MAX_VALUE, seed);
    }

    private void takeBounded(
            List<BuildingProfileSampleCandidate> remaining,
            List<Selected> selected,
            Map<BuildingProfileSampleStratum, Integer> populations,
            BuildingProfileSampleStratum stratum,
            Predicate<BuildingProfileSampleCandidate> predicate,
            int limit,
            String seed) {
        List<BuildingProfileSampleCandidate> population =
                remaining.stream().filter(predicate).toList();
        takePopulation(remaining, selected, populations, stratum, population, limit, seed);
    }

    private void takeLegalTransitions(
            List<BuildingProfileSampleCandidate> remaining,
            List<Selected> selected,
            Map<BuildingProfileSampleStratum, Integer> populations,
            String seed) {
        List<BuildingProfileSampleCandidate> population = remaining.stream()
                .filter(BuildingProfileSampleCandidate::legalCodeTransition)
                .toList();
        populations.put(BuildingProfileSampleStratum.LEGAL_CODE_TRANSITION, population.size());
        List<Selected> chosen = new ArrayList<>();
        for (String group : List.of("INCHEON", "GWANGJU_JEONNAM")) {
            population.stream()
                    .filter(candidate -> group.equals(candidate.legalTransitionGroup()))
                    .map(candidate -> new Selected(
                            candidate,
                            BuildingProfileSampleStratum.LEGAL_CODE_TRANSITION,
                            rank(seed, BuildingProfileSampleStratum.LEGAL_CODE_TRANSITION, candidate.pnu())))
                    .sorted(Comparator.comparingLong(Selected::rank)
                            .thenComparing(value -> value.candidate().pnu()))
                    .limit(50)
                    .forEach(chosen::add);
        }
        selected.addAll(chosen);
        Set<String> pnus =
                chosen.stream().map(value -> value.candidate().pnu()).collect(java.util.stream.Collectors.toSet());
        remaining.removeIf(candidate -> pnus.contains(candidate.pnu()));
    }

    private void takePopulation(
            List<BuildingProfileSampleCandidate> remaining,
            List<Selected> selected,
            Map<BuildingProfileSampleStratum, Integer> populations,
            BuildingProfileSampleStratum stratum,
            List<BuildingProfileSampleCandidate> population,
            int limit,
            String seed) {
        populations.put(stratum, population.size());
        List<Selected> chosen = population.stream()
                .map(candidate -> new Selected(candidate, stratum, rank(seed, stratum, candidate.pnu())))
                .sorted(Comparator.comparingLong(Selected::rank)
                        .thenComparing(value -> value.candidate().pnu()))
                .limit(limit)
                .toList();
        selected.addAll(chosen);
        Set<String> pnus =
                chosen.stream().map(value -> value.candidate().pnu()).collect(java.util.stream.Collectors.toSet());
        remaining.removeIf(candidate -> pnus.contains(candidate.pnu()));
    }

    private void takeRegional(
            List<BuildingProfileSampleCandidate> remaining, List<Selected> selected, int needed, String seed) {
        if (needed == 0) return;
        Map<String, List<BuildingProfileSampleCandidate>> byRegion = new LinkedHashMap<>();
        remaining.stream()
                .sorted(Comparator.comparing(BuildingProfileSampleCandidate::regionCode))
                .forEach(candidate -> byRegion.computeIfAbsent(candidate.regionCode(), ignored -> new ArrayList<>())
                        .add(candidate));
        Map<String, Integer> quota = new LinkedHashMap<>();
        int assigned = 0;
        for (var entry : byRegion.entrySet()) {
            int value = (int) Math.floor((double) entry.getValue().size() * needed / remaining.size());
            quota.put(entry.getKey(), value);
            assigned += value;
        }
        List<String> remainderOrder = byRegion.keySet().stream()
                .sorted(Comparator.<String>comparingDouble(
                                region -> -(((double) byRegion.get(region).size() * needed / remaining.size())
                                        - quota.get(region)))
                        .thenComparing(region -> region))
                .toList();
        for (int index = 0; assigned < needed; index++, assigned++) {
            String region = remainderOrder.get(index % remainderOrder.size());
            quota.merge(region, 1, Integer::sum);
        }
        for (var entry : byRegion.entrySet()) {
            entry.getValue().stream()
                    .map(candidate -> new Selected(
                            candidate,
                            BuildingProfileSampleStratum.REGIONAL_PROPORTIONAL,
                            rank(seed, BuildingProfileSampleStratum.REGIONAL_PROPORTIONAL, candidate.pnu())))
                    .sorted(Comparator.comparingLong(Selected::rank)
                            .thenComparing(value -> value.candidate().pnu()))
                    .limit(quota.get(entry.getKey()))
                    .forEach(selected::add);
        }
    }

    private long rank(String seed, BuildingProfileSampleStratum stratum, String pnu) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((seed + "\u0000" + stratum.name() + "\u0000" + pnu).getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, digest).longValue() & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireCapacity(List<Selected> selected, int sampleSize) {
        if (selected.size() > sampleSize) throw new IllegalArgumentException("shared PNU stratum exceeds sampleSize");
    }

    private record Selected(
            BuildingProfileSampleCandidate candidate, BuildingProfileSampleStratum stratum, long rank) {}
}
