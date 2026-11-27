package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "o11y")
public class O11yProperties {

    /** 成功请求的采样率。失败与低可信度请求永远保留。 */
    private double sampleRate = 0.2;
    /** 低于这个可信度的运行一律保留。 */
    private double trustFloor = 0.75;
    /** 内存里最多保留多少条 trace。 */
    private int storeCapacity = 400;
    /** 页面默认展示几个嫌疑。 */
    private int suspectLimit = 3;

    public double getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(double sampleRate) {
        this.sampleRate = sampleRate;
    }

    public double getTrustFloor() {
        return trustFloor;
    }

    public void setTrustFloor(double trustFloor) {
        this.trustFloor = trustFloor;
    }

    public int getStoreCapacity() {
        return storeCapacity;
    }

    public void setStoreCapacity(int storeCapacity) {
        this.storeCapacity = storeCapacity;
    }

    public int getSuspectLimit() {
        return suspectLimit;
    }

    public void setSuspectLimit(int suspectLimit) {
        this.suspectLimit = suspectLimit;
    }
}
