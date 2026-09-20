# Chronos Wave 4 — Benchmark Execution Script
param (
    [string]$Scales = "10000,50000,100000",
    [switch]$Quick
)

$ErrorActionPreference = "Stop"

# Ensure JDK 21
if (Test-Path "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot") {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
}

# Locate Maven
$mvnCmd = "mvn"
if (-not (Get-Command "mvn" -ErrorAction SilentlyContinue)) {
    $ideaMvn = "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.1\plugins\maven\lib\maven3\bin\mvn.cmd"
    if (Test-Path $ideaMvn) {
        $mvnCmd = $ideaMvn
    }
}

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "Starting Chronos Performance Benchmark Suite (Wave 4)" -ForegroundColor Cyan
Write-Host "Using Maven: $mvnCmd" -ForegroundColor Gray
Write-Host "Using Java: $env:JAVA_HOME" -ForegroundColor Gray
Write-Host "================================================================================" -ForegroundColor Cyan

# Ensure Docker containers chronos-postgres and chronos-redis are running
try {
    $runningContainers = docker ps --format "{{.Names}}"
    if ($runningContainers -notcontains "chronos-postgres") {
        Write-Host "Starting chronos-postgres container..." -ForegroundColor Yellow
        docker start chronos-postgres
    }
    if ($runningContainers -notcontains "chronos-redis") {
        Write-Host "Starting chronos-redis container..." -ForegroundColor Yellow
        docker start chronos-redis
    }
} catch {
    Write-Warning "Could not verify Docker containers automatically: $_"
}

# Compile test classes
Write-Host "Compiling test classes..." -ForegroundColor Yellow
& $mvnCmd test-compile

# Build execution arguments
$runnerArgs = if ($Quick) { "--quick" } else { "--scales=$Scales" }

Write-Host "Executing ChronosBenchmarkRunner with args: $runnerArgs" -ForegroundColor Green
& $mvnCmd exec:java@benchmark -Dexec.args="$runnerArgs"
