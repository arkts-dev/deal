#!/usr/bin/env python3
"""Compare cold compiler invocations over generated v1.5 projects (no test runner)."""
import argparse
import json
import os
from pathlib import Path
import statistics
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before-classes", required=True, type=Path)
    parser.add_argument("--after-classes", required=True, type=Path)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--backend", choices=["js", "lua", "jvm", "llvm", "all"], default="all")
    parser.add_argument("--json", type=Path, help="write raw samples and medians")
    args = parser.parse_args()
    if args.repetitions < 1 or args.timeout < 1:
        parser.error("repetitions and timeout must be positive")
    root = Path(__file__).resolve().parents[1]
    versions = {"before": args.before_classes.resolve(), "after": args.after_classes.resolve()}
    for directory in versions.values():
        if not (directory / "deal/Main.class").is_file():
            parser.error(f"missing compiler class: {directory / 'deal/Main.class'}")
    cases = [("tiny", 1, 1, 3), ("functions-100", 1, 100, 3),
             ("functions-300", 1, 300, 3), ("body-500", 1, 1, 500),
             ("modules-30", 30, 10, 3), ("classes-100", 1, 100, 3)]
    backends = ["js", "lua", "jvm", "llvm"] if args.backend == "all" else [args.backend]
    samples = []
    summaries = []
    with tempfile.TemporaryDirectory(prefix="deal-compiler-benchmark-") as temporary:
        work = Path(temporary)
        for name, modules, functions, statements in cases:
            project = work / name
            (project / "src").mkdir(parents=True)
            (project / "deal.json").write_text(json.dumps({"languageVersion": "1.5",
                "stdlib": "1.5", "moduleRoots": ["src"]}), encoding="utf-8")
            for module in range(modules):
                lines = [f'import * as next from "./m{module + 1}";'] if module + 1 < modules else []
                if name == "classes-100":
                    lines.extend(f"export class C{i} {{ x:number; text:string; }}" for i in range(functions))
                for function in range(functions):
                    lines.append(f"export function f{function}(x:number):number {{ let value:number=x;")
                    lines.extend(["value = value + 1;"] * statements)
                    lines.append("return value; }")
                if module == 0:
                    lines.append("export function main():null { let n:number=f0(0); return null; }")
                (project / "src" / f"m{module}.deal").write_text("\n".join(lines), encoding="utf-8")
            for backend in backends:
                if name == "classes-100" and backend == "llvm":
                    continue  # Callable export of a class is outside the staged native subset.
                for repetition in range(args.repetitions):
                    order = ["before", "after"] if repetition % 2 == 0 else ["after", "before"]
                    for version in order:
                        command = ["/usr/bin/time", "-f", "PERF %e %U %S %M",
                            "timeout", "--kill-after=5s", str(args.timeout),
                            "java", "-XX:ActiveProcessorCount=2", "-Xmx2g", "-cp", str(versions[version]),
                            "deal.Main", "compile", str(project / "src/m0.deal"),
                            "--backend", backend, "--output", str(project / (version + "-" + backend)), "--verbose"]
                        start = time.monotonic()
                        result = subprocess.run(command, cwd=root, env=os.environ.copy(),
                                                capture_output=True, text=True)
                        if result.returncode:
                            raise RuntimeError(f"{name}/{backend}/{version} failed ({result.returncode}):\n"
                                               + result.stdout + result.stderr)
                        metric = next(line.split() for line in result.stderr.splitlines() if line.startswith("PERF "))
                        sample = dict(case=name, backend=backend, version=version,
                            repetition=repetition, wall_seconds=time.monotonic() - start,
                            cpu_seconds=float(metric[2]) + float(metric[3]), rss_kib=int(metric[4]),
                            phases=[line.strip() for line in result.stdout.splitlines()
                                    if "total:" in line or "Total time:" in line])
                        samples.append(sample)
                summary = dict(case=name, backend=backend)
                for version in versions:
                    rows = [row for row in samples if row["case"] == name
                            and row["backend"] == backend and row["version"] == version]
                    summary[version] = {key: statistics.median(row[key] for row in rows)
                        for key in ("wall_seconds", "cpu_seconds", "rss_kib")}
                summaries.append(summary)
                print(json.dumps(summary), flush=True)
                if args.json:
                    args.json.write_text(json.dumps(dict(samples=samples, medians=summaries), indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
