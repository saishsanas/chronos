package com.chronos.benchmark.dataset;

public enum TopologyType {
    SINGLE_AGGREGATE("Topology A: Single Aggregate (Depth/Replay Focus)"),
    MULTI_AGGREGATE("Topology B: Multi-Aggregate (Projection/Scale Focus)");

    private final String description;

    TopologyType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
