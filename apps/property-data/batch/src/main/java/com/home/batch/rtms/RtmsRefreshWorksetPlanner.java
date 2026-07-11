package com.home.batch.rtms;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import com.home.application.region.RegionSiGunGuCodeReader;
import com.home.ingestcore.rtms.RtmsDealMonth;
import com.home.ingestcore.rtms.RtmsLawdCode;

public class RtmsRefreshWorksetPlanner {

	private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
	private static final int MAX_LOOKBACK_MONTHS = 24;

	private final RegionSiGunGuCodeReader lawdCodeReader;

	public RtmsRefreshWorksetPlanner(RegionSiGunGuCodeReader lawdCodeReader) {
		this.lawdCodeReader = Objects.requireNonNull(lawdCodeReader);
	}

	public List<RtmsRefreshWorkUnit> daily(LocalDate runDate, String configuredLawdCds, int lookbackMonths) {
		Objects.requireNonNull(runDate, "runDate is required");
		if (lookbackMonths < 0 || lookbackMonths > MAX_LOOKBACK_MONTHS) {
			throw new IllegalArgumentException("lookbackMonths must be between 0 and 24");
		}
		List<String> lawdCds = lawdCds(configuredLawdCds);
		if (lawdCds.isEmpty()) {
			lawdCds = lawdCodeReader.siGunGuCodes();
		}
		YearMonth baseMonth = YearMonth.from(runDate);
		List<String> dealYmds = IntStream.rangeClosed(0, lookbackMonths)
			.mapToObj(offset -> baseMonth.minusMonths(offset).format(MONTH_FORMATTER))
			.toList();
		return workUnits(lawdCds, dealYmds);
	}

	public List<RtmsRefreshWorkUnit> backfill(String fromYmd, String toYmd, String lawdCds) {
		YearMonth from = YearMonth.parse(RtmsDealMonth.of(fromYmd).value(), MONTH_FORMATTER);
		YearMonth to = YearMonth.parse(RtmsDealMonth.of(toYmd).value(), MONTH_FORMATTER);
		if (from.isAfter(to)) {
			throw new IllegalArgumentException("fromYmd must be less than or equal to toYmd");
		}
		List<String> months = new ArrayList<>();
		YearMonth current = from;
		while (!current.isAfter(to)) {
			months.add(current.format(MONTH_FORMATTER));
			current = current.plusMonths(1);
		}
		return workUnits(lawdCds(lawdCds), months);
	}

	private static List<RtmsRefreshWorkUnit> workUnits(List<String> lawdCds, List<String> dealYmds) {
		List<RtmsRefreshWorkUnit> units = new ArrayList<>();
		for (String lawdCd : lawdCds) {
			for (String dealYmd : dealYmds) {
				units.add(new RtmsRefreshWorkUnit(lawdCd, dealYmd));
			}
		}
		return List.copyOf(units);
	}

	private static List<String> lawdCds(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return Arrays.stream(value.split(","))
			.map(String::trim)
			.filter(lawdCd -> !lawdCd.isBlank())
			.map(lawdCd -> RtmsLawdCode.of(lawdCd).value())
			.distinct()
			.toList();
	}
}
