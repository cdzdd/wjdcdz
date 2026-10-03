package com.hengsheng.fund;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AdvancedAmountsTest {
    @Test void subscriptionInterestUsesCalendarDaysAndRoundsOnlyTheResult() {
        assertThat(AdvancedAmounts.interest(new BigDecimal("10000.00"), new BigDecimal("0.0365"),
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 22))).isEqualByComparingTo("10.00");
    }
    @Test void dividendsForTwoAccountsAreCalculatedSeparately() {
        assertThat(AdvancedAmounts.dividend(new BigDecimal("1000.0000"), new BigDecimal("0.10")))
                .isEqualByComparingTo("100.00");
        assertThat(AdvancedAmounts.dividend(new BigDecimal("500.0000"), new BigDecimal("0.10")))
                .isEqualByComparingTo("50.00");
    }
    @Test void dailyMoneyIncomeRetainsPrecisionAndAllowsLoss() {
        assertThat(AdvancedAmounts.moneyShares(new BigDecimal("10000.0000"), new BigDecimal("1.00")))
                .isEqualByComparingTo("1.0000");
        assertThat(AdvancedAmounts.moneyShares(new BigDecimal("10000.0000"), new BigDecimal("-0.25")))
                .isEqualByComparingTo("-0.2500");
    }
    @Test void conversionSupplementIsExternalFeeAndNeverNegative() {
        assertThat(AdvancedAmounts.supplement(new BigDecimal("1212.00"), new BigDecimal("0.00"), new BigDecimal("0.01")))
                .isEqualByComparingTo("12.00");
        assertThat(AdvancedAmounts.supplement(new BigDecimal("1212.00"), new BigDecimal("0.02"), new BigDecimal("0.01")))
                .isEqualByComparingTo("0.00");
    }
}
