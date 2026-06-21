package com.home.news;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "home.news")
public class NewsRuntimeProperties {

	private boolean enabled;
	private final RunOnce runOnce = new RunOnce();
	private final Naver naver = new Naver();
	private final OpenAi openai = new OpenAi();
	private final ResearchSeed researchSeed = new ResearchSeed();
	private final Pipeline pipeline = new Pipeline();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public RunOnce getRunOnce() {
		return runOnce;
	}

	public Naver getNaver() {
		return naver;
	}

	public OpenAi getOpenai() {
		return openai;
	}

	public ResearchSeed getResearchSeed() {
		return researchSeed;
	}

	public Pipeline getPipeline() {
		return pipeline;
	}

	public static class RunOnce {

		private boolean enabled;
		private String queryText = "";
		private int maxKeywords = 1;
		private int maxArticles = 10;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getQueryText() {
			return queryText;
		}

		public void setQueryText(String queryText) {
			this.queryText = queryText;
		}

		public int getMaxKeywords() {
			return maxKeywords;
		}

		public void setMaxKeywords(int maxKeywords) {
			this.maxKeywords = maxKeywords;
		}

		public int getMaxArticles() {
			return maxArticles;
		}

		public void setMaxArticles(int maxArticles) {
			this.maxArticles = maxArticles;
		}
	}

	public static class Naver {

		private String clientId = "";
		private String clientSecret = "";
		private int display = 10;
		private String sort = "date";
		private String baseUrl = "https://openapi.naver.com/v1/search/news.json";

		public String getClientId() {
			return clientId;
		}

		public void setClientId(String clientId) {
			this.clientId = clientId;
		}

		public String getClientSecret() {
			return clientSecret;
		}

		public void setClientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
		}

		public int getDisplay() {
			return display;
		}

		public void setDisplay(int display) {
			this.display = display;
		}

		public String getSort() {
			return sort;
		}

		public void setSort(String sort) {
			this.sort = sort;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}
	}

	public static class OpenAi {

		private boolean enabled = true;
		private String apiKey = "";
		private String model = "";
		private String baseUrl = "https://api.openai.com/v1/responses";
		private String extractionVersion = "naver-title-snippet-v1";
		private String promptVersion = "news-signal-v1";
		private String schemaVersion = "news-signal-json-v1";

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getApiKey() {
			return apiKey;
		}

		public void setApiKey(String apiKey) {
			this.apiKey = apiKey;
		}

		public String getModel() {
			return model;
		}

		public void setModel(String model) {
			this.model = model;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getExtractionVersion() {
			return extractionVersion;
		}

		public void setExtractionVersion(String extractionVersion) {
			this.extractionVersion = extractionVersion;
		}

		public String getPromptVersion() {
			return promptVersion;
		}

		public void setPromptVersion(String promptVersion) {
			this.promptVersion = promptVersion;
		}

		public String getSchemaVersion() {
			return schemaVersion;
		}

		public void setSchemaVersion(String schemaVersion) {
			this.schemaVersion = schemaVersion;
		}
	}

	public static class ResearchSeed {

		private boolean enabled;
		private String mode = "GENERATE_NOTES";
		private LocalDate periodStart = LocalDate.of(2017, 1, 1);
		private LocalDate periodEnd = LocalDate.of(2026, 5, 31);
		private int targetCandidatesPerBucket = 15;
		private String outputDir = "news-research-seed/obsidian";
		private int maxRequestsPerRun = 5;
		private String costCapUsd = "5.00";
		private String model = "gpt-5.4-2026-03-05";
		private String promptVersion = "research-seed-v2-gpt54";
		private String schemaVersion = "research-seed-schema-v2";
		private String screeningVersion = "research-seed-screening-v1";
		private String defaultReviewer = "local-operator";
		private final List<String> pilotBuckets = new ArrayList<>(List.of(
			"NATIONAL",
			"SEOUL_GANGNAM_GU",
			"SEOUL_SONGPA_GU",
			"GYEONGGI_SEONGNAM_SI",
			"GYEONGGI_GWACHEON_SI"
		));

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

		public int getTargetCandidatesPerBucket() {
			return targetCandidatesPerBucket;
		}

		public void setTargetCandidatesPerBucket(int targetCandidatesPerBucket) {
			this.targetCandidatesPerBucket = targetCandidatesPerBucket;
		}

		public String getOutputDir() {
			return outputDir;
		}

		public void setOutputDir(String outputDir) {
			this.outputDir = outputDir;
		}

		public int getMaxRequestsPerRun() {
			return maxRequestsPerRun;
		}

		public void setMaxRequestsPerRun(int maxRequestsPerRun) {
			this.maxRequestsPerRun = maxRequestsPerRun;
		}

		public String getCostCapUsd() {
			return costCapUsd;
		}

		public void setCostCapUsd(String costCapUsd) {
			this.costCapUsd = costCapUsd;
		}

		public String getModel() {
			return model;
		}

		public void setModel(String model) {
			this.model = model;
		}

		public String getPromptVersion() {
			return promptVersion;
		}

		public void setPromptVersion(String promptVersion) {
			this.promptVersion = promptVersion;
		}

		public String getSchemaVersion() {
			return schemaVersion;
		}

		public void setSchemaVersion(String schemaVersion) {
			this.schemaVersion = schemaVersion;
		}

		public String getScreeningVersion() {
			return screeningVersion;
		}

		public void setScreeningVersion(String screeningVersion) {
			this.screeningVersion = screeningVersion;
		}

		public String getDefaultReviewer() {
			return defaultReviewer;
		}

		public void setDefaultReviewer(String defaultReviewer) {
			this.defaultReviewer = defaultReviewer;
		}

		public List<String> getPilotBuckets() {
			return pilotBuckets;
		}
	}

	public static class Pipeline {

		private final Daily daily = new Daily();

		public Daily getDaily() {
			return daily;
		}
	}

	public static class Daily {

		private boolean enabled;
		private String cron = "0 0 4 * * *";
		private String zone = "Asia/Seoul";
		private final List<String> pilotQueries = new ArrayList<>();

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getCron() {
			return cron;
		}

		public void setCron(String cron) {
			this.cron = cron;
		}

		public String getZone() {
			return zone;
		}

		public void setZone(String zone) {
			this.zone = zone;
		}

		public List<String> getPilotQueries() {
			return pilotQueries;
		}
	}
}
