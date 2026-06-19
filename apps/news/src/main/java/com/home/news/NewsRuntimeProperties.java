package com.home.news;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "home.news")
public class NewsRuntimeProperties {

	private boolean enabled;
	private final RunOnce runOnce = new RunOnce();
	private final Naver naver = new Naver();
	private final OpenAi openai = new OpenAi();

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
}
