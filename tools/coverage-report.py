"""Report production-only coverage and enforce unrounded coverage minima."""
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

MINIMUMS = {"LINE": 80, "BRANCH": 69}


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


def main():
    java, cli = sys.argv[1:]
    build = Path("build")
    # Production compiles into build/deal; tests compile into build/test-classes.
    classes = build / "deal"
    if not any(classes.rglob("*.class")):
        raise SystemExit("ERROR: no production class files")
    subprocess.run([
        java, "-jar", cli, "report", str(build / "jacoco.exec"),
        "--classfiles", str(classes), "--sourcefiles", ".",
        "--csv", str(build / "coverage.csv"),
        "--xml", str(build / "coverage.xml"),
        "--html", str(build / "coverage-html"),
    ], check=True)
    check_coverage(ET.parse(build / "coverage.xml").getroot())


if __name__ == "__main__":
    main()
