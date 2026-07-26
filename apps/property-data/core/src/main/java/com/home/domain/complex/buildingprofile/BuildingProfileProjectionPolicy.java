package com.home.domain.complex.buildingprofile;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class BuildingProfileProjectionPolicy {
    private static final BigDecimal MINIMUM_READINESS = new BigDecimal("0.4492986425");
    private static final Set<BuildingProfileField> FIELDS = Collections.unmodifiableSet(EnumSet.of(
            BuildingProfileField.ATCH_BLD_AREA,
            BuildingProfileField.EMGEN_USE_ELVT_CNT,
            BuildingProfileField.ENGR_EPI,
            BuildingProfileField.ENGR_RAT,
            BuildingProfileField.ETC_PURPS,
            BuildingProfileField.ETC_ROOF,
            BuildingProfileField.ETC_STRCT,
            BuildingProfileField.GN_BLD_CERT,
            BuildingProfileField.HO_CNT,
            BuildingProfileField.INDR_AUTO_AREA,
            BuildingProfileField.INDR_AUTO_UTCNT,
            BuildingProfileField.INDR_MECH_AREA,
            BuildingProfileField.INDR_MECH_UTCNT,
            BuildingProfileField.ITG_BLD_CERT,
            BuildingProfileField.MAIN_ATCH_GB_CD,
            BuildingProfileField.MAIN_ATCH_GB_CD_NM,
            BuildingProfileField.MAIN_PURPS_CD,
            BuildingProfileField.MAIN_PURPS_CD_NM,
            BuildingProfileField.OUDR_AUTO_AREA,
            BuildingProfileField.OUDR_AUTO_UTCNT,
            BuildingProfileField.OUDR_MECH_AREA,
            BuildingProfileField.OUDR_MECH_UTCNT,
            BuildingProfileField.RIDE_USE_ELVT_CNT,
            BuildingProfileField.ROOF_CD,
            BuildingProfileField.ROOF_CD_NM,
            BuildingProfileField.RSERTHQK_DSGN_APPLY_YN,
            BuildingProfileField.STRCT_CD,
            BuildingProfileField.STRCT_CD_NM,
            BuildingProfileField.UGRND_FLR_CNT,
            BuildingProfileField.DONG_NM,
            BuildingProfileField.GRND_FLR_CNT,
            BuildingProfileField.HEIT,
            BuildingProfileField.ATCH_BLD_CNT,
            BuildingProfileField.BJDONG_CD,
            BuildingProfileField.BLD_NM,
            BuildingProfileField.BUN,
            BuildingProfileField.BYLOT_CNT,
            BuildingProfileField.CRTN_DAY,
            BuildingProfileField.FMLY_CNT,
            BuildingProfileField.HHLD_CNT,
            BuildingProfileField.JI,
            BuildingProfileField.NEW_PLAT_PLC,
            BuildingProfileField.PLAT_AREA,
            BuildingProfileField.PLAT_GB_CD,
            BuildingProfileField.PLAT_PLC,
            BuildingProfileField.PMS_DAY,
            BuildingProfileField.ROAD_BJDONG_CD,
            BuildingProfileField.ROAD_CD,
            BuildingProfileField.ROAD_MAIN_NO,
            BuildingProfileField.ROAD_SUB_NO,
            BuildingProfileField.ROAD_UNDERGROUND_CD,
            BuildingProfileField.SIGUNGU_CD,
            BuildingProfileField.STCNS_DAY,
            BuildingProfileField.TOT_DONG_TOT_AREA,
            BuildingProfileField.USE_APR_DAY));

    public BigDecimal minimumReadiness() {
        return MINIMUM_READINESS;
    }

    public Set<BuildingProfileField> fields() {
        return FIELDS;
    }

    public boolean eligible(BigDecimal readiness) {
        return readiness != null && readiness.compareTo(MINIMUM_READINESS) >= 0;
    }

    public BuildingProfileProjectionUse use(BuildingProfileField field, BuildingProfileQualityTier tier) {
        if (!FIELDS.contains(field)) {
            throw new IllegalArgumentException("field is not part of the normalized profile projection");
        }
        if (field == BuildingProfileField.STCNS_DAY || field == BuildingProfileField.USE_APR_DAY) {
            return BuildingProfileProjectionUse.OBSERVATION_ONLY;
        }
        if (tier == BuildingProfileQualityTier.PROMOTE_CANDIDATE) {
            return BuildingProfileProjectionUse.OPERATIONAL;
        }
        return BuildingProfileProjectionUse.PROFILE_ONLY;
    }
}
