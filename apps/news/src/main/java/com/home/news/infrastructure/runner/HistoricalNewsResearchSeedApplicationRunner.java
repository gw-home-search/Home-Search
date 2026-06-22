package com.home.news.infrastructure.runner;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.NewsResearchSeedMode;
import com.home.news.NewsRuntimeProperties;
import com.home.news.application.BigKindsCsvResearchNoteGenerator;
import com.home.news.application.HistoricalNewsCandidate;
import com.home.news.application.HistoricalNewsCsvNoteWriteResult;
import com.home.news.application.HistoricalNewsCsvShortlistWriteResult;
import com.home.news.application.HistoricalNewsNoteWriteResult;
import com.home.news.application.HistoricalNewsResearchClient;
import com.home.news.application.HistoricalNewsResearchNoteGenerator;
import com.home.news.application.HistoricalNewsResearchRequest;
import com.home.news.application.HistoricalNewsSeedImporter;
import com.home.news.application.NewsCollectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public class HistoricalNewsResearchSeedApplicationRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(HistoricalNewsResearchSeedApplicationRunner.class);

	private final HistoricalNewsResearchClient researchClient;
	private final HistoricalNewsResearchNoteGenerator noteGenerator;
	private final BigKindsCsvResearchNoteGenerator csvNoteGenerator;
	private final HistoricalNewsSeedImporter importer;
	private final NewsRuntimeProperties properties;

	public HistoricalNewsResearchSeedApplicationRunner(
		HistoricalNewsResearchClient researchClient,
		HistoricalNewsResearchNoteGenerator noteGenerator,
		BigKindsCsvResearchNoteGenerator csvNoteGenerator,
		HistoricalNewsSeedImporter importer,
		NewsRuntimeProperties properties
	) {
		this.researchClient = researchClient;
		this.noteGenerator = noteGenerator;
		this.csvNoteGenerator = csvNoteGenerator;
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
			List<HistoricalNewsCandidate> candidates = new ArrayList<>();
			for (HistoricalNewsResearchRequest request : requests()) {
				candidates.addAll(researchClient.research(request).candidates());
			}
			HistoricalNewsNoteWriteResult result = noteGenerator.writeNotes(outputRoot, candidates);
			log.info(
				"Historical research seed notes generated: candidates={} accepted={} notes={} rejected={} rejected_by_reason={} output_root={}",
				result.candidateCount(),
				result.acceptedCount(),
				result.noteCount(),
				result.rejectedCount(),
				result.rejectedByReason(),
				result.outputRoot()
			);
			return;
		}
		if (mode == NewsResearchSeedMode.GENERATE_CSV_NOTES) {
			Path csvInputRoot = Path.of(properties.getResearchSeed().getCsvInputDir());
			HistoricalNewsCsvNoteWriteResult result = csvNoteGenerator.writeNotes(csvInputRoot, outputRoot);
			log.info(
				"BigKinds CSV research seed notes generated: files={} notes={} skipped_files={} skipped_by_reason={} output_root={}",
				result.fileCount(),
				result.generatedCount(),
				result.skippedFileCount(),
				result.skippedByReason(),
				result.outputRoot()
			);
			return;
		}
		if (mode == NewsResearchSeedMode.GENERATE_CSV_SHORTLIST) {
			Path csvInputRoot = Path.of(properties.getResearchSeed().getCsvInputDir());
			HistoricalNewsCsvShortlistWriteResult result = csvNoteGenerator.writeShortlists(csvInputRoot, outputRoot);
			log.info(
				"BigKinds CSV research seed shortlist generated: files={} months={} candidates={} skipped_files={} skipped_by_reason={} output_root={}",
				result.fileCount(),
				result.monthCount(),
				result.candidateCount(),
				result.skippedFileCount(),
				result.skippedByReason(),
				result.outputRoot()
			);
			return;
		}
		if (mode == NewsResearchSeedMode.IMPORT_APPROVED) {
			importer.importApprovedNotes(outputRoot);
			return;
		}
		throw new NewsCollectionException("unsupported research seed mode: " + mode);
	}

	private List<HistoricalNewsResearchRequest> requests() {
		NewsRuntimeProperties.ResearchSeed seed = properties.getResearchSeed();
		int limit = Math.max(1, seed.getMaxRequestsPerRun());
		List<NewsRegionBucket> buckets = buckets(seed);
		List<HistoricalNewsResearchRequest> requests = new ArrayList<>();
		YearMonth current = YearMonth.from(seed.getPeriodStart());
		YearMonth end = YearMonth.from(seed.getPeriodEnd());
		while (!current.isAfter(end) && requests.size() < limit) {
			for (NewsRegionBucket bucket : buckets) {
				if (requests.size() >= limit) {
					break;
				}
				requests.add(new HistoricalNewsResearchRequest(current, bucket, seed.getTargetCandidatesPerBucket()));
			}
			current = current.plusMonths(1);
		}
		return List.copyOf(requests);
	}

	private List<NewsRegionBucket> buckets(NewsRuntimeProperties.ResearchSeed seed) {
		return seed.getPilotBuckets().stream()
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
