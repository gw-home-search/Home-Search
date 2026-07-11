package com.home.migration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;

final class FlywayRepairPreflight {
	private static final int APPLIED_V1_CHECKSUM = 2_040_410_589;
	private static final int RESOLVED_V1_CHECKSUM = 1_472_119_118;

	private FlywayRepairPreflight() {}

	static Decision verify(MigrationInfo[] migrations) {
		return verify(java.util.Arrays.stream(migrations).map(MigrationSnapshot::from).toList());
	}

	static Decision verify(List<MigrationSnapshot> migrations) {
		Map<String, MigrationSnapshot> byVersion = migrations.stream()
			.collect(Collectors.toMap(MigrationSnapshot::version, Function.identity()));
		MigrationSnapshot v1 = required(byVersion, "1");
		MigrationSnapshot v2 = required(byVersion, "2");
		MigrationSnapshot v3 = required(byVersion, "3");
		MigrationSnapshot v4 = required(byVersion, "4");
		boolean alignV1 = !java.util.Objects.equals(v1.appliedChecksum(), v1.resolvedChecksum());
		if (v1.state() != MigrationState.SUCCESS || (alignV1 && !(Integer.valueOf(APPLIED_V1_CHECKSUM).equals(v1.appliedChecksum())
			&& Integer.valueOf(RESOLVED_V1_CHECKSUM).equals(v1.resolvedChecksum())))) {
			throw new MigrationOperationException("Repair preflight failed: unexpected V1 checksum state");
		}
		if (v2.state() != MigrationState.SUCCESS || !sameChecksum(v2)) {
			throw new MigrationOperationException("Repair preflight failed: V2 is not a matching success");
		}
		if (v3.state() != MigrationState.MISSING_SUCCESS) {
			throw new MigrationOperationException("Repair preflight failed: V3 is not the only missing applied migration");
		}
		if (v4.state() != MigrationState.SUCCESS || !sameChecksum(v4)) {
			throw new MigrationOperationException("Repair preflight failed: V4 is not a matching success");
		}
		boolean unexpected = migrations.stream().anyMatch(item -> {
			int version;
			try { version = Integer.parseInt(item.version()); }
			catch (NumberFormatException exception) { return true; }
			return version > 4 && item.state() != MigrationState.PENDING;
		});
		if (unexpected) throw new MigrationOperationException("Repair preflight failed: unexpected post-V4 migration state");
		return new Decision(alignV1);
	}

	private static MigrationSnapshot required(Map<String, MigrationSnapshot> migrations, String version) {
		MigrationSnapshot value = migrations.get(version);
		if (value == null) throw new MigrationOperationException("Repair preflight failed: V" + version + " is absent");
		return value;
	}

	private static boolean sameChecksum(MigrationSnapshot value) {
		return java.util.Objects.equals(value.appliedChecksum(), value.resolvedChecksum());
	}

	record Decision(boolean alignV1) {}
	record MigrationSnapshot(String version, MigrationState state, Integer appliedChecksum, Integer resolvedChecksum) {
		static MigrationSnapshot from(MigrationInfo info) {
			String version = info.getVersion() == null ? "repeatable" : info.getVersion().getVersion();
			return new MigrationSnapshot(version, info.getState(),
				info.getAppliedChecksum(), info.getResolvedChecksum());
		}
	}
}
