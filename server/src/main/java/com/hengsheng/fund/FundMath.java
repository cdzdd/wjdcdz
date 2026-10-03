package com.hengsheng.fund;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class FundMath {
    private FundMath() {}
    public record Purchase(BigDecimal fee, BigDecimal netAmount, BigDecimal shares, BigDecimal roundingDifference) {}
    public static Purchase purchase(BigDecimal amount, BigDecimal rate, BigDecimal fixedFee, BigDecimal nav) {
        if(amount.signum()<=0||nav.signum()<=0||rate.signum()<0||(fixedFee!=null&&(fixedFee.signum()<0||fixedFee.compareTo(amount)>=0)))throw new IllegalArgumentException("本金、净值和费率必须有效，固定费用须小于本金");
        BigDecimal exactNet = fixedFee == null ? amount.divide(BigDecimal.ONE.add(rate),12,RoundingMode.HALF_UP) : amount.subtract(fixedFee);
        BigDecimal net = exactNet.setScale(2,RoundingMode.HALF_UP);
        if(net.signum()<=0)throw new IllegalArgumentException("净申购金额须为正");
        BigDecimal shares = net.divide(nav,12,RoundingMode.HALF_UP).setScale(4,RoundingMode.HALF_UP);
        return new Purchase(amount.subtract(net).setScale(2,RoundingMode.HALF_UP),net,shares,net.subtract(shares.multiply(nav)).setScale(12,RoundingMode.HALF_UP));
    }
    public static BigDecimal redemptionFee(BigDecimal shares, BigDecimal nav, long heldDays, BigDecimal baseRate) {
        BigDecimal rate=heldDays < 7 ? baseRate.max(new BigDecimal("0.015")) : baseRate;
        return shares.multiply(nav).multiply(rate).setScale(2,RoundingMode.HALF_UP);
    }
}
