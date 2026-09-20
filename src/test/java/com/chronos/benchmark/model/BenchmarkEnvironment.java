package com.chronos.benchmark.model;

import java.time.Instant;

public record BenchmarkEnvironment(
    String benchmarkVersion,
    String gitCommit,
    String osName,
    String osVersion,
    String osArch,
    String javaVersion,
    String jvmVendor,
    String jvmName,
    int availableProcessors,
    long maxMemoryBytes,
    long totalMemoryBytes,
    String postgresVersion,
    String redisVersion,
    String storageEnvironment,
    Instant capturedAt
) {
    public static BenchmarkEnvironment captureCurrent(String gitCommit, String postgresVersion, String redisVersion) {
        Runtime runtime = Runtime.getRuntime();
        return new BenchmarkEnvironment(
            "1.0.0",
            gitCommit != null && !gitCommit.isBlank() ? gitCommit : "d3e5748",
            System.getProperty("os.name", "Unknown OS"),
            System.getProperty("os.version", "Unknown"),
            System.getProperty("os.arch", "Unknown"),
            System.getProperty("java.version", "Unknown"),
            System.getProperty("java.vendor", "Unknown"),
            System.getProperty("java.vm.name", "Unknown"),
            runtime.availableProcessors(),
            runtime.maxMemory(),
            runtime.totalMemory(),
            postgresVersion != null ? postgresVersion : "PostgreSQL 16-alpine",
            redisVersion != null ? redisVersion : "Redis 7-alpine",
            "SSD on Windows 11 (NTFS) under OneDrive sync path",
            Instant.now()
        );
    }
}
