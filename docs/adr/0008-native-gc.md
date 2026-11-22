# 0008 Native Image garbage collector

- Status: Proposed (decision after M10 measurements)

## Context
Native Image offers Serial GC (default; CE and Oracle) and G1 (Oracle GraalVM, Linux only). Epsilon is for testing only. The tail-latency targets are p99.9 of 5 ms or less, and the memory target is an idle RSS of 64 MB or less.

## Options
1. Serial GC with a tuned young generation (`-XX:MaximumYoungGenerationSizePercent`, `-Xmn`).
2. G1 (`--gc=G1`) on Linux.

## Decision (proposed)
- Develop and test on Serial, which is the only option on macOS.
- In M10, measure Serial and G1 on Linux under the same workload (throughput, p99/p99.9, peak RSS, pause times) and pick per deployment profile.
- Try PGO (`--pgo-instrument` then `--pgo`) on the plain-proxy and full-pipeline workloads.

## Impact
- Performance: G1 is expected to give better tails under high allocation, and Serial better RSS. To be measured.
- Memory: G1 has a higher baseline RSS.
- Native Image: G1 needs Oracle GraalVM on Linux. The CE fallback is Serial.
- Operational: GC flags are recorded per overlay in M9.

**M9 amendment (2026-11-22).** The native images are built with `NATIVE_GC=serial` (Docker build argument, passed through as
`-Pgw.nativeGc`). `--build-arg NATIVE_GC=G1` builds a G1 image (Oracle GraalVM on Linux only). The GC is fixed at image build
time, so the choice is made per image tag and not per overlay. The heap is sized per overlay through container arguments
(`-Xmx320m` gateway and `-Xmx192m` control plane in the base; dev lowers only the requests). The memory limit is kept about 1.5× above `-Xmx` for off-heap
overhead (thread stacks, Netty direct buffers, image heap). M10 still decides Serial vs G1 from Linux measurements.

## Native Image flags in effect (M1)
- `-H:+SharedArenaSupport` (experimental, unlocked in a scope). Netty 4.2 on JDK 25 frees direct buffers through `Arena.ofShared` (`CleanerJava25`), which Netty chose deliberately for native images. Without the flag, buffer release throws `UnsupportedFeatureError` on event loops. M1 hit this on the first native run, at shutdown. Revisit with each GraalVM update, and remove the flag if shared arenas become default.
- `-march=compatibility` for portable binaries. Benchmark builds in M10 also try `-march=native`.
