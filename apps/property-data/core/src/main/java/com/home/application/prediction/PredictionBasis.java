package com.home.application.prediction;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PredictionBasis(
        Long complexId,
        Long tradeId,
        LocalDate dealDate,
        BigDecimal dealAmount,
        Integer floor,
        BigDecimal areaM2,
        String pnu,
        LocalDate useDate) {}
