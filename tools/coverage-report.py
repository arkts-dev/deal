from pathlib import Path
import re, struct, shutil, subprocess, sys, xml.etree.ElementTree as ET
root = Path.cwd()
out = root / 'build'
java, cli = sys.argv[1:]
MIN_LINE = 80.0
MIN_BRANCH = 69.0
production = set()
for p in (root / 'deal').rglob('*.java'):
    m = re.search(r'^\s*package\s+([\w.]+)\s*;', p.read_text(), re.M)
    production.add(((m[1].replace('.', '/') + '/') if m else '') + p.name)
def source_file(data):
    pos = 8
    def u2():
        nonlocal pos
        v = struct.unpack_from('>H', data, pos)[0]; pos += 2; return v
    def u4():
        nonlocal pos
        v = struct.unpack_from('>I', data, pos)[0]; pos += 4; return v
    n = u2(); cp = {}; i = 1
    while i < n:
        tag = data[pos]; pos += 1
        if tag == 1:
            length = u2(); cp[i] = data[pos:pos+length].decode('utf-8', errors='replace'); pos += length
        elif tag in (3,4,9,10,11,12,17,18): pos += 4
        elif tag in (5,6): pos += 8; i += 1
        elif tag in (7,8,16,19,20): pos += 2
        elif tag == 15: pos += 3
        else: raise ValueError(tag)
        i += 1
    pos += 6
    interfaces = u2(); pos += 2 * interfaces
    for _ in range(2):
        for _ in range(u2()):
            pos += 6
            for _ in range(u2()):
                u2(); length = u4(); pos += length
    for _ in range(u2()):
        name = cp[u2()]; length = u4()
        if name == 'SourceFile': return cp[u2()]
        pos += length
    raise ValueError('SourceFile missing')
shutil.rmtree(out/'production-classes', ignore_errors=True)
count = 0
for p in list((root/'build/deal').rglob('*.class')):
    relative = p.relative_to(root/'build')
    source = source_file(p.read_bytes())
    if (relative.parent / source).as_posix() in production:
        dest = out/'production-classes'/relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(p, dest); count += 1
print('Production class files:', count)
subprocess.run([java, '-jar', cli, 'report', str(out/'jacoco.exec'), '--classfiles', str(out/'production-classes'), '--sourcefiles', str(root), '--csv', str(out/'coverage.csv'), '--xml', str(out/'coverage.xml'), '--html', str(out/'coverage-html')], check=True)
report = ET.parse(out/'coverage.xml').getroot()
passed = True
for counter in report.findall('counter'):
    kind = counter.attrib['type']
    if kind in ('LINE', 'BRANCH'):
        covered = int(counter.attrib['covered'])
        missed = int(counter.attrib['missed'])
        pct = 100 * covered / (covered + missed) if covered + missed else 0
        minimum = MIN_LINE if kind == 'LINE' else MIN_BRANCH
        print(f"{kind} coverage: {covered}/{covered + missed} = {pct:.2f}% (minimum {minimum:g}%)")
        passed &= pct >= minimum
if not passed:
    raise SystemExit('ERROR: coverage below minimum')
print('=== Coverage passed ===')
