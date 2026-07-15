package com.home.sourcedata.migration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;

class LegacyCoordinateSourceFingerprintTest {
    private final LegacyCoordinateSourceFingerprint fingerprint = new LegacyCoordinateSourceFingerprint();

    @Test
    void acceptsOnlyKnownLegacyFingerprint() {
        assertThatCode(() -> fingerprint.assertMatches(LegacyCoordinateSourceFingerprint.expectedSnapshot()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingLegacyTable() {
        var expected = LegacyCoordinateSourceFingerprint.expectedSnapshot();
        var tables = new LinkedHashSet<>(expected.tables());
        tables.remove("parcel_coordinate_snapshot");

        assertThatThrownBy(() -> fingerprint.assertMatches(new LegacyCoordinateSourceFingerprint.LegacySchemaSnapshot(
                        tables, expected.columns(), expected.constraints(), expected.indexes())))
                .isInstanceOf(LegacyCoordinateSourceFingerprint.LegacyFingerprintMismatchException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rejectsUnexpectedColumn() {
        var expected = LegacyCoordinateSourceFingerprint.expectedSnapshot();
        var columns = new LinkedHashSet<>(expected.columns());
        columns.add("parcel_coordinate_snapshot|unknown|text|text|YES|NO|||");

        assertThatThrownBy(() -> fingerprint.assertMatches(new LegacyCoordinateSourceFingerprint.LegacySchemaSnapshot(
                        expected.tables(), columns, expected.constraints(), expected.indexes())))
                .isInstanceOf(LegacyCoordinateSourceFingerprint.LegacyFingerprintMismatchException.class)
                .hasMessageContaining("unexpected");
    }
}
