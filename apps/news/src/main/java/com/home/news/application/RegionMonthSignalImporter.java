package com.home.news.application;

import java.nio.file.Path;
import java.util.List;

import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.RegionMonthSignalSourceKind;
import com.home.news.infrastructure.persistence.JdbcRegionMonthSignalRepository;

public class RegionMonthSignalImporter {

	private final RegionMonthSignalJsonl jsonl;
	private final JdbcRegionMonthSignalRepository repository;

	public RegionMonthSignalImporter(RegionMonthSignalJsonl jsonl, JdbcRegionMonthSignalRepository repository) {
		this.jsonl = jsonl;
		this.repository = repository;
	}

	public RegionMonthSignalImportResult importJsonl(Path path) {
		List<RegionMonthSignalSnapshot> snapshots = jsonl.read(path);
		if (snapshots.isEmpty()) {
			return new RegionMonthSignalImportResult(0, 0, 0, 0);
		}
		RegionMonthSignalSourceKind sourceKind = snapshots.get(0).sourceKind();
		String methodVersion = snapshots.get(0).methodVersion();
		NewsModelDatasetTier datasetTier = snapshots.get(0).datasetTier();
		long runId = repository.startImportRun(sourceKind, methodVersion, datasetTier, path.toString());
		int evidenceCount = 0;
		try {
			for (RegionMonthSignalSnapshot snapshot : snapshots) {
				long snapshotId = repository.upsertSnapshot(snapshot, runId);
				evidenceCount += repository.replaceEvidence(snapshotId, snapshot.evidence());
			}
			repository.finishImportRun(runId, snapshots.size(), snapshots.size(), evidenceCount, "SUCCEEDED", null);
			return new RegionMonthSignalImportResult(runId, snapshots.size(), snapshots.size(), evidenceCount);
		}
		catch (RuntimeException ex) {
			repository.finishImportRun(runId, snapshots.size(), 0, evidenceCount, "FAILED", ex.getMessage());
			throw ex;
		}
	}
}
