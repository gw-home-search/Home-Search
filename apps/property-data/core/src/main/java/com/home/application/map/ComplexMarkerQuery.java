package com.home.application.map;

import java.math.BigDecimal;

public record ComplexMarkerQuery(
        Double swLat,
        Double swLng,
        Double neLat,
        Double neLng,
        Integer pyeongMin,
        Integer pyeongMax,
        Double priceEokMin,
        Double priceEokMax,
        Integer ageMin,
        Integer ageMax,
        Long unitMin,
        Long unitMax,
        BigDecimal bcRatMin,
        BigDecimal bcRatMax,
        BigDecimal vlRatMin,
        BigDecimal vlRatMax) {

    public ComplexMarkerQuery(
            Double swLat,
            Double swLng,
            Double neLat,
            Double neLng,
            Integer pyeongMin,
            Integer pyeongMax,
            Double priceEokMin,
            Double priceEokMax,
            Integer ageMin,
            Integer ageMax,
            Long unitMin,
            Long unitMax) {
        this(
                swLat,
                swLng,
                neLat,
                neLng,
                pyeongMin,
                pyeongMax,
                priceEokMin,
                priceEokMax,
                ageMin,
                ageMax,
                unitMin,
                unitMax,
                null,
                null,
                null,
                null);
    }
}
