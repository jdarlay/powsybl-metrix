package com.powsybl.metrix.mapping.timeseries;

public class MappedPower {
    private String timeSeriesNameP = null;

    private Double minP = null;

    private Double valueP = null;

    private Double maxP = null;

    private boolean ignoreLimits;

    public MappedPower() {
    }

    public MappedPower(MappedPower mappedPower) {
        this.timeSeriesNameP = mappedPower.timeSeriesNameP;
        this.minP = mappedPower.minP;
        this.valueP = mappedPower.valueP;
        this.maxP = mappedPower.maxP;
        this.ignoreLimits = mappedPower.ignoreLimits;
    }

    public String getTimeSeriesNameP() {
        return timeSeriesNameP;
    }

    public void setTimeSeriesNameP(String timeSeriesNameP) {
        this.timeSeriesNameP = timeSeriesNameP;
    }

    public Double getMinP() {
        return minP;
    }

    public void setMinP(Double minP) {
        this.minP = minP;
    }

    public Double getP() {
        return valueP;
    }

    public void setP(Double valueP) {
        this.valueP = valueP;
    }

    public Double getMaxP() {
        return maxP;
    }

    public void setMaxP(Double maxP) {
        this.maxP = maxP;
    }

    public boolean isIgnoreLimits() {
        return ignoreLimits;
    }

    public void setIgnoreLimits(boolean ignoreLimits) {
        this.ignoreLimits = ignoreLimits;
    }
}
