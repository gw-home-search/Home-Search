package com.home.domain.complex.buildingprofile;

public record BuildingProfileStratumStats(
        BuildingProfileSampleStratum stratum, int populationCount, int sampleCount, double samplingWeight) {}
