package com.hengsheng.fund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FundMathTest {
    private BigDecimal d(String s) { return new BigDecimal(s); }
    @Test void externalPurchaseFeeAndSharesAreRoundedIndependently() {
        var result = FundMath.purchase(d("1000.00"), d("0.015"), null, d("1.234567"));
        assertThat(result.netAmount()).isEqualByComparingTo("985.22");
        assertThat(result.fee()).isEqualByComparingTo("14.78");
        assertThat(result.shares()).isEqualByComparingTo("798.0288");
    }
    @Test void fixedFeeAndShortHoldingRedemptionUseTheirOwnRules() {
        var result = FundMath.purchase(d("1000"), d("0.015"), d("5"), d("1"));
        assertThat(result.shares()).isEqualByComparingTo("995.0000");
        assertThat(FundMath.redemptionFee(d("1000"),d("1.2"),6,d("0.005"))).isEqualByComparingTo("18.00");
        assertThat(FundMath.redemptionFee(d("1000"),d("1.2"),7,d("0.005"))).isEqualByComparingTo("6.00");
    }
    @Test void fixedFeeCannotConsumeAllOrMoreOfThePrincipal(){
        assertThatThrownBy(()->FundMath.purchase(d("1000"),d("0"),d("1000"),d("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->FundMath.purchase(d("1000"),d("0"),d("1001"),d("1"))).isInstanceOf(IllegalArgumentException.class);
    }
}
