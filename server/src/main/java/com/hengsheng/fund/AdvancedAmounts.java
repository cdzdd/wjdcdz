package com.hengsheng.fund;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.math.RoundingMode;

final class AdvancedAmounts {
    private AdvancedAmounts() {}
    static BigDecimal interest(BigDecimal principal, BigDecimal annualRate, LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end);
        if (days < 0) throw new IllegalArgumentException("募集结束日不能早于认购日");
        return principal.multiply(annualRate).multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(365), 12, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
    }
    static BigDecimal dividend(BigDecimal shares, BigDecimal perShare) {
        return shares.multiply(perShare).setScale(2, RoundingMode.HALF_UP);
    }
    static BigDecimal moneyShares(BigDecimal shares, BigDecimal incomePerTenThousand) {
        return shares.multiply(incomePerTenThousand).divide(BigDecimal.valueOf(10000), 12, RoundingMode.HALF_UP);
    }
    static BigDecimal supplement(BigDecimal proceeds, BigDecimal sourceRate, BigDecimal targetRate) {
        BigDecimal rate = targetRate.subtract(sourceRate).max(BigDecimal.ZERO);
        return proceeds.subtract(proceeds.divide(BigDecimal.ONE.add(rate), 12, RoundingMode.HALF_UP))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
