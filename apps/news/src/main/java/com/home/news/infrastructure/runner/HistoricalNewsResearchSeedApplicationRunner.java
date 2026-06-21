package com.home.news.infrastructure.runner;

import java.nio.file.Path;
import java.util.List;

import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.NewsResearchSeedMode;
import com.home.news.NewsRuntimeProperties;
import com.home.news.application.HistoricalNewsResearchClient;
import com.home.news.application.HistoricalNewsResearchNoteGenerator;
import com.home.news.application.HistoricalNewsResearchRequest;
import com.home.news.application.HistoricalNewsSeedImporter;
import com.home.news.application.NewsCollectionException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public class HistoricalNewsResearchSeedApplicationRunner implements ApplicationRunner {

	private final HistoricalNewsResearchClient researchClient;
	private final HistoricalNewsResearchNoteGenerator noteGenerator;
	private final HistoricalNewsSeedImporter importer;
	private final NewsRuntimeProperties properties;

	public HistoricalNewsResearchSeedApplicationRunner(
		HistoricalNewsResearchClient researchClient,
		HistoricalNewsResearchNoteGenerator noteGenerator,
		HistoricalNewsSeedImporter importer,
		NewsRuntimeProperties properties
	) {
		this.researchClient = researchClient;
		this.noteGenerator = noteGenerator;
		this.importer = importer;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.getResearchSeed().isEnabled()) {
			return;
		}
		NewsResearchSeedMode mode = mode();
		if (mode == NewsResearchSeedMode.DRY_RUN) {
			return;
		}
		Path outputRoot = Path.of(properties.getResearchSeed().getOutputDir());
		if (mode == NewsResearchSeedMode.GENERATE_NOTES) {
			noteGenerator.writeNotes(outputRoot, researchClient.research(request()).candidates());
			return;
		}
		if (mode == NewsResearchSeedMode.IMPORT_APPROVED) {
			importer.importApprovedNotes(outputRoot);
			return;
		}
		throw new NewsCollectionException("unsupported research seed mode: " + mode);
	}

	private HistoricalNewsResearchRequest request() {
		NewsRuntimeProperties.ResearchSeed seed = properties.getResearchSeed();
		return new HistoricalNewsResearchRequest(
			seed.getPeriodStart(),
			seed.getPeriodEnd(),
			buckets(seed),
			seed.getTargetCandidatesPerBucket()
		);
	}

	private List<NewsRegionBucket> buckets(NewsRuntimeProperties.ResearchSeed seed) {
		int limit = Math.max(1, seed.getMaxRequestsPerRun());
		return seed.getPilotBuckets().stream()
			.limit(limit)
			.map(NewsRegionBucket::valueOf)
			.toList();
	}

	private NewsResearchSeedMode mode() {
		try {
			return NewsResearchSeedMode.valueOf(properties.getResearchSeed().getMode());
		}
		catch (IllegalArgumentException ex) {
			throw new NewsCollectionException("home.news.research-seed.mode is invalid: " + properties.getResearchSeed().getMode(), ex);
		}
	}
}
