package com.turbofeed.gateway.service.review;

/**
 * 严格度对应的阈值档案（feature-match M3）。
 *
 * <p>{@code passThreshold}：机审 {@code confidence} ≥ 此值才自动放行(先发后审)；
 * 低于则落入「强制送人审(先审后放)」梯度分支，不论账号信用等级。</p>
 */
public class StrictnessProfile {

    private double passThreshold = 0.9;

    public StrictnessProfile() {
    }

    public StrictnessProfile(double passThreshold) {
        this.passThreshold = passThreshold;
    }

    public double getPassThreshold() {
        return passThreshold;
    }

    public void setPassThreshold(double passThreshold) {
        this.passThreshold = passThreshold;
    }
}
