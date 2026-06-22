package com.home.news;

import java.time.LocalDate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "home.news")
public class NewsRuntimeProperties {

	private boolean enabled;
	private final RegionMonthSignals regionMonthSignals = new RegionMonthSignals();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public RegionMonthSignals getRegionMonthSignals() {
		return regionMonthSignals;
	}

	public static class RegionMonthSignals {

		private boolean enabled;
		private String mode = "GENERATE_CSV_REGION_MONTH_SIGNALS";
		private LocalDate periodStart = LocalDate.of(2017, 1, 1);
		private LocalDate periodEnd = LocalDate.of(2026, 5, 31);
		private String methodVersion = "region-month-signal-v1";
		private String csvInputDir = "apps/news/local-input/historical-news-csv";
		private String webWorklistPath = "apps/news/local-input/region-month-signal-web-worklist.jsonl";
		private String webResearchPath = "apps/news/local-input/region-month-signal-web-research.jsonl";
		private String generatedCsvSignalsPath = "apps/news/local-input/region-month-signal-bigkinds.csv.jsonl";
		private String obsidianOutputDir = "news-research-seed/obsidian";

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getMode() {
			return mode;
		}

		public void setMode(String mode) {
			this.mode = mode;
		}

		public LocalDate getPeriodStart() {
			return periodStart;
		}

		public void setPeriodStart(LocalDate periodStart) {
			this.periodStart = periodStart;
		}

		public LocalDate getPeriodEnd() {
			return periodEnd;
		}

		public void setPeriodEnd(LocalDate periodEnd) {
			this.periodEnd = periodEnd;
		}

		public String getMethodVersion() {
			return methodVersion;
		}

		public void setMethodVersion(String methodVersion) {
			this.methodVersion = methodVersion;
		}

		public String getCsvInputDir() {
			return csvInputDir;
		}

		public void setCsvInputDir(String csvInputDir) {
			this.csvInputDir = csvInputDir;
		}

		public String getWebWorklistPath() {
			return webWorklistPath;
		}

		public void setWebWorklistPath(String webWorklistPath) {
			this.webWorklistPath = webWorklistPath;
		}

		public String getWebResearchPath() {
			return webResearchPath;
		}

		public void setWebResearchPath(String webResearchPath) {
			this.webResearchPath = webResearchPath;
		}

		public String getGeneratedCsvSignalsPath() {
			return generatedCsvSignalsPath;
		}

		public void setGeneratedCsvSignalsPath(String generatedCsvSignalsPath) {
			this.generatedCsvSignalsPath = generatedCsvSignalsPath;
		}

		public String getObsidianOutputDir() {
			return obsidianOutputDir;
		}

		public void setObsidianOutputDir(String obsidianOutputDir) {
			this.obsidianOutputDir = obsidianOutputDir;
		}
	}
}
