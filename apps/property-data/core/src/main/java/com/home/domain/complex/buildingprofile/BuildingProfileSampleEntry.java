package com.home.domain.complex.buildingprofile;

public record BuildingProfileSampleEntry(
        String pnu, BuildingProfileSampleStratum stratum, long seedRank, double samplingWeight, int complexCount) {}
