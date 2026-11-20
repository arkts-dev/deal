"""Report production-only coverage and enforce unrounded coverage minima."""
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

MINIMUMS = {"LINE": 85, "BRANCH": 75}


def check_coverage(report):
    counters = {counter.get("type"): counter for counter in report.findall("counter")}
    passed = True
    for kind, minimum in MINIMUMS.items():
        counter = counters.get(kind)
        if counter is None:
            raise SystemExit(f"ERROR: missing {kind} coverage counter")
        covered, missed = int(counter.get("covered")), int(counter.get("missed"))
        total = covered + missed
        if covered < 0 or missed < 0 or total == 0:
            raise SystemExit(f"ERROR: invalid {kind} coverage counter")
        print(f"{kind} coverage: {covered}/{total} = {100 * covered / total:.2f}% "
              f"(minimum {minimum}%)")
        passed &= covered * 100 >= minimum * total
    if not passed:
        raise SystemExit("ERROR: coverage below minimum")
    print("=== Coverage passed ===")


def merge_coverage(java, cli, build):
    # Each launcher owns one file, including nested Java children. Force-killed
    # compiler workers cannot run JaCoCo's shutdown hook and leave an empty file;
    # these contributed no data under the old shared-file workflow either.
    directory = build / "jacoco-processes"
    files = sorted(directory.glob("process-*.exec"))
    inputs = [path for path in files if path.stat().st_size != 0]
    if not inputs:
        raise SystemExit("ERROR: no Java process coverage data")
    print(f"Coverage inputs: {len(inputs)} completed dumps, {len(files) - len(inputs)} empty files", flush=True)
    destination = build / "jacoco.exec"
    destination.unlink(missing_ok=True)
    # Thousands of JVM launches must not exceed execve's argument limit. Each
    # level has distinct outputs; no merge reads a file it is overwriting.
    with tempfile.TemporaryDirectory(prefix="jacoco-merge-", dir=build) as temporary:
        level = 0
        while len(inputs) > 128:
            outputs = []
            for start in range(0, len(inputs), 128):
                output = Path(temporary) / f"{level}-{start}.exec"
                subprocess.run([java, "-jar", cli, "merge",
                                *map(str, inputs[start:start + 128]),
                                "--destfile", str(output)], check=True)
                outputs.append(output)
            inputs = outputs
            level += 1
        subprocess.run([java, "-jar", cli, "merge", *map(str, inputs),
                        "--destfile", str(destination)], check=True)
    return destination


def main():
    java, cli = sys.argv[1:]
    build = Path("build")
    # Production compiles into build/deal; tests compile into build/test-classes.
    classes = build / "deal"
    if not any(classes.rglob("*.class")):
        raise SystemExit("ERROR: no production class files")
    merged = merge_coverage(java, cli, build)
    subprocess.run([
        java, "-jar", cli, "report", str(merged),
        "--classfiles", str(classes), "--sourcefiles", ".",
        "--csv", str(build / "coverage.csv"),
        "--xml", str(build / "coverage.xml"),
        "--html", str(build / "coverage-html"),
    ], check=True)
    check_coverage(ET.parse(build / "coverage.xml").getroot())


if __name__ == "__main__":
    main()
