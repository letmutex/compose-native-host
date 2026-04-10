#!/usr/bin/env bun

import { spawnSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";

type SampleBenchmark = {
    label: string;
    task: string;
};

type PhaseStats = {
    phase: string;
    values: number[];
};

const RunCount = 10;
const WarmupCount = 3;
const RunTimeoutMs = 5_000;
const PhaseRegex = /\[NativeHost\] \[(\d+(?:\.\d+)?) ms\] Phase: (.+)$/;

const samples: SampleBenchmark[] = [
    { label: "AppKit", task: ":composeNativeHostSampleAppkit:nativeRun" },
    { label: "SwiftUi", task: ":composeNativeHostSampleSwiftUi:nativeRun" },
    { label: "Mixed", task: ":composeNativeHostSampleMixed:nativeRun" },
];

const repoRoot = path.resolve(import.meta.dir, "..", "..");
const configuredRunCount = readPositiveInt(process.env.RUNS, RunCount);
const configuredWarmupCount = readNonNegativeInt(process.env.WARMUPS, WarmupCount);
const configuredTimeoutMs = readPositiveInt(process.env.TIMEOUT_MS, RunTimeoutMs);
const selectedLabels = new Set((process.env.SAMPLES ?? "").split(",").map((value) => value.trim()).filter(Boolean));
const timeoutCommand = resolveTimeoutCommand();

function main() {
    const selectedSamples = samples.filter(sample => selectedLabels.size === 0 || selectedLabels.has(sample.label));
    for (const sample of selectedSamples) {
        const phasesByName = new Map<string, PhaseStats>();
        const phaseOrder: string[] = [];

        for (let index = 0; index < configuredWarmupCount; index += 1) {
            runSample(sample.task);
        }

        for (let index = 0; index < configuredRunCount; index += 1) {
            const output = runSample(sample.task);
            const phaseMatches = parsePhases(output);

            for (const match of phaseMatches) {
                let stats = phasesByName.get(match.phase);
                if (!stats) {
                    stats = { phase: match.phase, values: [] };
                    phasesByName.set(match.phase, stats);
                    phaseOrder.push(match.phase);
                }
                stats.values.push(match.timeMs);
            }
        }

        printSummary(sample.label, phaseOrder, phasesByName);
    }
}

function runSample(task: string): string {
    const result = timeoutCommand
        ? spawnSync(timeoutCommand, [formatTimeoutSeconds(configuredTimeoutMs), "./gradlew", task], {
            cwd: repoRoot,
            encoding: "utf8",
            maxBuffer: 16 * 1024 * 1024,
        })
        : spawnSync("./gradlew", [task], {
            cwd: repoRoot,
            encoding: "utf8",
            timeout: configuredTimeoutMs,
            maxBuffer: 16 * 1024 * 1024,
        });
    return `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
}

function resolveTimeoutCommand() {
    const candidates = [
        "/opt/homebrew/bin/timeout",
        "/usr/local/bin/timeout",
        "/opt/homebrew/bin/gtimeout",
        "/usr/local/bin/gtimeout",
        "/usr/bin/timeout",
    ];
    return candidates.find(candidate => fs.existsSync(candidate)) ?? null;
}

function formatTimeoutSeconds(timeoutMs: number) {
    return `${Math.ceil(timeoutMs / 1000)}s`;
}

function parsePhases(output: string) {
    const matches: Array<{ phase: string; timeMs: number }> = [];
    for (const line of output.split(/\r?\n/)) {
        const match = line.match(PhaseRegex);
        if (!match) {
            continue;
        }
        matches.push({
            timeMs: Number(match[1]),
            phase: match[2],
        });
    }
    return matches;
}

function printSummary(
    label: string,
    phaseOrder: string[],
    phasesByName: Map<string, PhaseStats>,
) {
    console.log(`== ${label} ==`);
    if (phaseOrder.length === 0) {
        console.log("[NativeHost] No phase timings captured.");
        console.log("");
        return;
    }
    for (const phase of phaseOrder) {
        const stats = phasesByName.get(phase);
        if (!stats || stats.values.length === 0) {
            continue;
        }
        const min = Math.min(...stats.values);
        const max = Math.max(...stats.values);
        const mean = stats.values.reduce((sum, value) => sum + value, 0) / stats.values.length;
        console.log(
            `[NativeHost] [${formatMs(min)}/${formatMs(mean)}/${formatMs(max)} ms] Phase: ${phase}`,
        );
    }
    console.log("");
}

function formatMs(value: number) {
    return value.toFixed(2);
}

function readPositiveInt(rawValue: string | undefined, fallback: number) {
    if (!rawValue) {
        return fallback;
    }
    const value = Number.parseInt(rawValue, 10);
    return Number.isFinite(value) && value > 0 ? value : fallback;
}

function readNonNegativeInt(rawValue: string | undefined, fallback: number) {
    if (!rawValue) {
        return fallback;
    }
    const value = Number.parseInt(rawValue, 10);
    return Number.isFinite(value) && value >= 0 ? value : fallback;
}

main();
