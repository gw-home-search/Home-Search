package com.home.rtmsloader;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "home.rtms-loader")
public class RtmsLoaderProperties {

	private static final DateTimeFormatter DEAL_YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

	private boolean enabled;
	private String mode = RtmsLoaderBoundary.MONTHLY_BULK_MODE;
	private List<String> lawdCds = List.of();
	private String baseDealYmd;
	private int lookbackMonths;

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

	public List<String> getLawdCds() {
		return lawdCds;
	}

	public void setLawdCds(List<String> lawdCds) {
		this.lawdCds = lawdCds == null ? List.of() : List.copyOf(lawdCds);
	}

	public String getBaseDealYmd() {
		return baseDealYmd;
	}

	public void setBaseDealYmd(String baseDealYmd) {
		this.baseDealYmd = baseDealYmd;
	}

	public int getLookbackMonths() {
		return lookbackMonths;
	}

	public void setLookbackMonths(int lookbackMonths) {
		this.lookbackMonths = lookbackMonths;
	}

	RtmsLoaderJobRequest toRequest(Clock clock) {
		String dealYmd = baseDealYmd;
		if (dealYmd == null || dealYmd.isBlank()) {
			dealYmd = YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).format(DEAL_YMD_FORMATTER);
		}
		return new RtmsLoaderJobRequest(
			RtmsLoaderMode.from(mode),
			lawdCds,
			dealYmd,
			lookbackMonths
		);
	}
}
