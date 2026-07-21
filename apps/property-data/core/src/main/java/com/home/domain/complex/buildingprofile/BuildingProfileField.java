package com.home.domain.complex.buildingprofile;

import static com.home.domain.complex.buildingprofile.BuildingProfileAggregation.*;
import static com.home.domain.complex.buildingprofile.BuildingProfileScope.*;
import static com.home.domain.complex.buildingprofile.BuildingProfileValueType.*;
import static com.home.domain.complex.buildingprofile.BuildingProfileZeroPolicy.*;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public enum BuildingProfileField {
    MGM_BLDRGST_PK(HIERARCHY, TEXT, VALID, DIRECT, "mgmBldrgstPk", "MGM_BLDRGST_PK", "bldMgmBldRgstPk"),
    MGM_UP_BLDRGST_PK(HIERARCHY, TEXT, VALID, DIRECT, "mgmUpBldrgstPk", "MGM_UP_BLDRGST_PK"),
    REGSTR_GB_CD(HIERARCHY, TEXT, VALID, SET, "regstrGbCd", "REGSTR_GB_CD"),
    REGSTR_GB_CD_NM(HIERARCHY, TEXT, VALID, SET, "regstrGbCdNm", "REGSTR_GB_CD_NM"),
    REGSTR_KIND_CD(HIERARCHY, TEXT, VALID, SET, "regstrKindCd", "REGSTR_KIND_CD"),
    REGSTR_KIND_CD_NM(HIERARCHY, TEXT, VALID, SET, "regstrKindCdNm", "REGSTR_KIND_CD_NM"),
    NEW_OLD_REGSTR_GB_CD(HIERARCHY, TEXT, VALID, SET, "newOldRegstrGbCd", "NEW_OLD_REGSTR_GB_CD"),
    NEW_OLD_REGSTR_GB_CD_NM(HIERARCHY, TEXT, VALID, SET, "newOldRegstrGbCdNm", "NEW_OLD_REGSTR_GB_CD_NM"),
    RNUM(HIERARCHY, INTEGER, INVALID, DIRECT, "rnum", "RNUM"),
    MAIN_ATCH_GB_CD(BUILDING, TEXT, VALID, SET, "mainAtchGbCd", "MAIN_ATCH_GB_CD"),
    MAIN_ATCH_GB_CD_NM(BUILDING, TEXT, VALID, SET, "mainAtchGbCdNm", "MAIN_ATCH_GB_CD_NM"),
    BLD_NM(SITE, TEXT, VALID, SET, "bldNm", "BLD_NM"),
    DONG_NM(BUILDING, TEXT, VALID, SET, "dongNm", "DONG_NM"),
    PLAT_PLC(SITE, TEXT, VALID, CONSENSUS, "platPlc", "PLAT_PLC"),
    NEW_PLAT_PLC(SITE, TEXT, VALID, CONSENSUS, "newPlatPlc", "NEW_PLAT_PLC"),
    SIGUNGU_CD(SITE, TEXT, VALID, CONSENSUS, "sigunguCd", "SIGUNGU_CD"),
    BJDONG_CD(SITE, TEXT, VALID, CONSENSUS, "bjdongCd", "BJDONG_CD"),
    PLAT_GB_CD(SITE, TEXT, VALID, CONSENSUS, "platGbCd", "PLAT_GB_CD"),
    BUN(SITE, TEXT, VALID, CONSENSUS, "bun", "BUN"),
    JI(SITE, TEXT, VALID, CONSENSUS, "ji", "JI"),
    SPLOT_NM(SITE, TEXT, VALID, CONSENSUS, "splotNm", "SPLOT_NM"),
    BLOCK(SITE, TEXT, VALID, CONSENSUS, "block", "BLOCK"),
    LOT(SITE, TEXT, VALID, CONSENSUS, "lot", "LOT"),
    BYLOT_CNT(SITE, INTEGER, VALID, CONSENSUS, "bylotCnt", "BYLOT_CNT"),
    ROAD_CD(SITE, TEXT, VALID, CONSENSUS, "naRoadCd", "NA_ROAD_CD"),
    ROAD_BJDONG_CD(SITE, TEXT, VALID, CONSENSUS, "naBjdongCd", "NA_BJDONG_CD"),
    ROAD_UNDERGROUND_CD(SITE, TEXT, VALID, CONSENSUS, "naUgrndCd", "NA_UGRND_CD"),
    ROAD_MAIN_NO(SITE, TEXT, VALID, CONSENSUS, "naMainBun", "NA_MAIN_BUN"),
    ROAD_SUB_NO(SITE, TEXT, VALID, CONSENSUS, "naSubBun", "NA_SUB_BUN"),

    PLAT_AREA(SITE, DECIMAL, MISSING_EQUIVALENT, CONSENSUS, "platArea", "PLAT_AREA"),
    ARCH_AREA(BUILDING, DECIMAL, MISSING_EQUIVALENT, SUM, "archArea", "ARCH_AREA"),
    TOT_AREA(BUILDING, DECIMAL, MISSING_EQUIVALENT, SUM, "totArea", "TOT_AREA"),
    VL_RAT_ESTM_TOT_AREA(BUILDING, DECIMAL, MISSING_EQUIVALENT, SUM, "vlRatEstmTotArea", "VL_RAT_ESTM_TOT_AREA"),
    BC_RAT(SITE, DECIMAL, MISSING_EQUIVALENT, RECALCULATED, "bcRat", "BC_RAT"),
    VL_RAT(SITE, DECIMAL, MISSING_EQUIVALENT, RECALCULATED, "vlRat", "VL_RAT"),
    ATCH_BLD_AREA(BUILDING, DECIMAL, VALID, DIRECT, "atchBldArea", "ATCH_BLD_AREA"),
    TOT_DONG_TOT_AREA(SITE, DECIMAL, MISSING_EQUIVALENT, DIRECT, "totDongTotArea", "TOT_DONG_TOT_AREA"),

    HHLD_CNT(SITE, INTEGER, VALID, SUM, "hhldCnt", "HHLD_CNT"),
    FMLY_CNT(SITE, INTEGER, VALID, SUM, "fmlyCnt", "FMLY_CNT"),
    HO_CNT(BUILDING, INTEGER, VALID, SUM, "hoCnt", "HO_CNT"),
    MAIN_BLD_CNT(SITE, INTEGER, VALID, DIRECT, "mainBldCnt", "MAIN_BLD_CNT"),
    ATCH_BLD_CNT(SITE, INTEGER, VALID, DIRECT, "atchBldCnt", "ATCH_BLD_CNT"),

    TOT_PKNG_CNT(SITE, INTEGER, VALID, SUM, "totPkngCnt", "TOT_PKNG_CNT"),
    INDR_MECH_UTCNT(BUILDING, INTEGER, VALID, SUM, "indrMechUtcnt", "INDR_MECH_UTCNT"),
    INDR_MECH_AREA(BUILDING, DECIMAL, VALID, SUM, "indrMechArea", "INDR_MECH_AREA"),
    OUDR_MECH_UTCNT(BUILDING, INTEGER, VALID, SUM, "oudrMechUtcnt", "OUDR_MECH_UTCNT"),
    OUDR_MECH_AREA(BUILDING, DECIMAL, VALID, SUM, "oudrMechArea", "OUDR_MECH_AREA"),
    INDR_AUTO_UTCNT(BUILDING, INTEGER, VALID, SUM, "indrAutoUtcnt", "INDR_AUTO_UTCNT"),
    INDR_AUTO_AREA(BUILDING, DECIMAL, VALID, SUM, "indrAutoArea", "INDR_AUTO_AREA"),
    OUDR_AUTO_UTCNT(BUILDING, INTEGER, VALID, SUM, "oudrAutoUtcnt", "OUDR_AUTO_UTCNT"),
    OUDR_AUTO_AREA(BUILDING, DECIMAL, VALID, SUM, "oudrAutoArea", "OUDR_AUTO_AREA"),

    HEIT(BUILDING, DECIMAL, MISSING_EQUIVALENT, MAX, "heit", "HEIT"),
    GRND_FLR_CNT(BUILDING, INTEGER, MISSING_EQUIVALENT, MAX, "grndFlrCnt", "GRND_FLR_CNT"),
    UGRND_FLR_CNT(BUILDING, INTEGER, VALID, MAX, "ugrndFlrCnt", "UGRND_FLR_CNT"),
    RIDE_USE_ELVT_CNT(BUILDING, INTEGER, VALID, SUM, "rideUseElvtCnt", "RIDE_USE_ELVT_CNT"),
    EMGEN_USE_ELVT_CNT(BUILDING, INTEGER, VALID, SUM, "emgenUseElvtCnt", "EMGEN_USE_ELVT_CNT"),
    STRCT_CD(BUILDING, TEXT, VALID, SET, "strctCd", "STRCT_CD"),
    STRCT_CD_NM(BUILDING, TEXT, VALID, SET, "strctCdNm", "STRCT_CD_NM"),
    ETC_STRCT(BUILDING, TEXT, VALID, SET, "etcStrct", "ETC_STRCT"),
    ROOF_CD(BUILDING, TEXT, VALID, SET, "roofCd", "ROOF_CD"),
    ROOF_CD_NM(BUILDING, TEXT, VALID, SET, "roofCdNm", "ROOF_CD_NM"),
    ETC_ROOF(BUILDING, TEXT, VALID, SET, "etcRoof", "ETC_ROOF"),
    MAIN_PURPS_CD(BUILDING, TEXT, VALID, SET, "mainPurpsCd", "MAIN_PURPS_CD"),
    MAIN_PURPS_CD_NM(BUILDING, TEXT, VALID, SET, "mainPurpsCdNm", "MAIN_PURPS_CD_NM"),
    ETC_PURPS(BUILDING, TEXT, VALID, SET, "etcPurps", "ETC_PURPS"),

    RSERTHQK_DSGN_APPLY_YN(BUILDING, BOOLEAN, VALID, SET, "rserthqkDsgnApplyYn", "RSERTHQK_DSGN_APPLY_YN"),
    RSERTHQK_ABILITY(BUILDING, TEXT, VALID, SET, "rserthqkAblty", "rserthqkAbility", "RSERTHQK_ABILITY"),

    PMS_DAY(SITE, DATE, INVALID, CONSENSUS, "pmsDay", "PMS_DAY"),
    STCNS_DAY(SITE, DATE, INVALID, CONSENSUS, "stcnsDay", "STCNS_DAY"),
    USE_APR_DAY(SITE, DATE, INVALID, CONSENSUS, "useAprDay", "USE_APR_DAY"),
    CRTN_DAY(SITE, DATE, INVALID, CONSENSUS, "crtnDay", "CRTN_DAY"),
    PMSNO_YEAR(SITE, TEXT, VALID, CONSENSUS, "pmsnoYear", "PMSNO_YEAR"),
    PMSNO_KIK_CD(SITE, TEXT, VALID, CONSENSUS, "pmsnoKikCd", "PMSNO_KIK_CD"),
    PMSNO_KIK_CD_NM(SITE, TEXT, VALID, CONSENSUS, "pmsnoKikCdNm", "PMSNO_KIK_CD_NM"),
    PMSNO_GB_CD(SITE, TEXT, VALID, CONSENSUS, "pmsnoGbCd", "PMSNO_GB_CD"),
    PMSNO_GB_CD_NM(SITE, TEXT, VALID, CONSENSUS, "pmsnoGbCdNm", "PMSNO_GB_CD_NM"),

    ENGR_GRADE(BUILDING, TEXT, VALID, SET, "engrGrade", "ENGR_GRADE"),
    ENGR_RAT(BUILDING, DECIMAL, VALID, SET, "engrRat", "ENGR_RAT"),
    ENGR_EPI(BUILDING, DECIMAL, VALID, SET, "engrEpi", "ENGR_EPI"),
    GN_BLD_GRADE(BUILDING, TEXT, VALID, SET, "gnBldGrade", "GN_BLD_GRADE"),
    GN_BLD_CERT(BUILDING, DECIMAL, VALID, SET, "gnBldCert", "GN_BLD_CERT"),
    ITG_BLD_GRADE(BUILDING, TEXT, VALID, SET, "itgBldGrade", "ITG_BLD_GRADE"),
    ITG_BLD_CERT(BUILDING, DECIMAL, VALID, SET, "itgBldCert", "ITG_BLD_CERT");

    private static final Set<BuildingProfileField> BASIC_FIELDS =
            Set.of(MGM_BLDRGST_PK, MGM_UP_BLDRGST_PK, REGSTR_GB_CD, REGSTR_KIND_CD, NEW_OLD_REGSTR_GB_CD);

    private final BuildingProfileScope scope;
    private final BuildingProfileValueType valueType;
    private final BuildingProfileZeroPolicy zeroPolicy;
    private final BuildingProfileAggregation aggregation;
    private final Set<String> providerKeys;

    BuildingProfileField(
            BuildingProfileScope scope,
            BuildingProfileValueType valueType,
            BuildingProfileZeroPolicy zeroPolicy,
            BuildingProfileAggregation aggregation,
            String... providerKeys) {
        this.scope = scope;
        this.valueType = valueType;
        this.zeroPolicy = zeroPolicy;
        this.aggregation = aggregation;
        this.providerKeys = Set.copyOf(new LinkedHashSet<>(Arrays.asList(providerKeys)));
    }

    public BuildingProfileScope scope() {
        return scope;
    }

    public BuildingProfileValueType valueType() {
        return valueType;
    }

    public BuildingProfileZeroPolicy zeroPolicy() {
        return zeroPolicy;
    }

    public BuildingProfileAggregation aggregation() {
        return aggregation;
    }

    public Set<String> providerKeys() {
        return providerKeys;
    }

    public boolean hierarchyLeanField() {
        return BASIC_FIELDS.contains(this);
    }

    public String titleKo() {
        return "건축물대장 필드 " + name();
    }

    public String descriptionKo() {
        return "건축물대장 " + scope.titleKo() + " 범위의 " + valueType.titleKo() + " 형식 필드";
    }

    public static Optional<BuildingProfileField> fromProviderKey(String key) {
        if (key == null) return Optional.empty();
        String normalized = key.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(field -> field.providerKeys.stream()
                        .map(candidate -> candidate.toLowerCase(Locale.ROOT))
                        .anyMatch(normalized::equals))
                .findFirst();
    }
}
