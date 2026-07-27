package com.home.application.read;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TradeAreaResult(BigDecimal exclArea, long tradeCount, LocalDate latestDealDate) {}
