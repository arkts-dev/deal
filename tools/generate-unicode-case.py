#!/usr/bin/env python3
"""Generate backend-local Unicode 17.0 default case data from a UCD directory.

Inputs: https://www.unicode.org/Public/17.0.0/ucd/{UnicodeData,SpecialCasing,DerivedCoreProperties}.txt
Unicode data license: https://www.unicode.org/license.txt
No locale-sensitive mappings are included; Final_Sigma is implemented by each runtime.
"""
import hashlib
import json
import re
from pathlib import Path
import sys

HASHES = {
    'UnicodeData.txt': '2e1efc1dcb59c575eedf5ccae60f95229f706ee6d031835247d843c11d96470c',
    'SpecialCasing.txt': 'efc25faf19de21b92c1194c111c932e03d2a5eaf18194e33f1156e96de4c9588',
    'DerivedCoreProperties.txt': '24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08',
}
LICENSE = '''UNICODE LICENSE V3
Copyright © 1991-2026 Unicode, Inc.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.'''
root = Path(__file__).resolve().parent.parent
ucd = Path(sys.argv[1])
texts = {}
for name, digest in HASHES.items():
    data = (ucd / name).read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise SystemExit('Unexpected Unicode 17.0 input: ' + name)
    texts[name] = data.decode('utf-8')
lower, upper = {}, {}
for line in texts['UnicodeData.txt'].splitlines():
    fields = line.split(';')
    cp = int(fields[0], 16)
    if fields[13]: lower[cp] = [int(fields[13], 16)]
    if fields[12]: upper[cp] = [int(fields[12], 16)]
for line in texts['SpecialCasing.txt'].splitlines():
    fields = line.split('#')[0].strip().split(';')
    if len(fields) < 5 or fields[4].strip(): continue
    cp = int(fields[0], 16)
    lower[cp] = [int(x, 16) for x in fields[1].split()]
    upper[cp] = [int(x, 16) for x in fields[3].split()]
for mapping in (lower, upper):
    for cp in list(mapping):
        if mapping[cp] == [cp]: del mapping[cp]
properties = {'Cased': [], 'Case_Ignorable': []}
for line in texts['DerivedCoreProperties.txt'].splitlines():
    fields = line.split('#')[0].strip().split(';')
    if len(fields) != 2 or fields[1].strip() not in properties: continue
    endpoints = [int(x, 16) for x in fields[0].strip().split('..')]
    properties[fields[1].strip()].append((endpoints[0], endpoints[-1]))

def encoded(mapping):
    return ';'.join(','.join(format(x, 'x') for x in [cp] + values) for cp, values in sorted(mapping.items()))

def ranges(name):
    return ','.join(format(x, 'x') for pair in properties[name] for x in pair)

java = '''package deal.semantic.ir;

/** Generated Unicode 17.0 default case data; regenerate with tools/generate-unicode-case.py.
 * Unicode data: https://www.unicode.org/license.txt */
final class UnicodeCaseData {
    private UnicodeCaseData() {}
    private static int[][] parse(String text, String separator) {
        String[] rows = text.split(separator);
        int[][] result = new int[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            String[] values = rows[i].split(",");
            result[i] = new int[values.length];
            for (int j = 0; j < values.length; j++) result[i][j] = Integer.parseInt(values[j], 16);
        }
        return result;
    }
'''
for name, mapping in [('LOWER', lower), ('UPPER', upper)]:
    java += '    static final int[][] ' + name + ' = parse("' + encoded(mapping) + '", ";");\n'
for name, prop in [('CASED', 'Cased'), ('IGNORABLE', 'Case_Ignorable')]:
    java += '    static final int[] ' + name + ' = parse("' + ranges(prop) + '", ";")[0];\n'
java += '}\n'
(root / 'deal/semantic/ir/UnicodeCaseData.java').write_text('/* ' + LICENSE + ' */\n' + java)

header = '''/* Generated Unicode 17.0 default case data; tools/generate-unicode-case.py.
 * Unicode data: https://www.unicode.org/license.txt */
#ifndef DEAL_UNICODE_CASE_H
#define DEAL_UNICODE_CASE_H
#include <stdint.h>
typedef struct deal_case_mapping { uint32_t source; uint32_t mapped[3]; unsigned count; } deal_case_mapping;
'''
for name, mapping in [('lower', lower), ('upper', upper)]:
    header += 'static const deal_case_mapping deal_case_' + name + '[] = {\n'
    for cp, values in sorted(mapping.items()):
        assert 1 <= len(values) <= 3
        header += '    {%du, {%s}, %du},\n' % (cp, ', '.join(str(x) + 'u' for x in values), len(values))
    header += '};\n'
for name, prop in [('cased', 'Cased'), ('ignorable', 'Case_Ignorable')]:
    header += 'static const uint32_t deal_case_' + name + '[][2] = {\n'
    for a, b in properties[prop]: header += '    {%du, %du},\n' % (a, b)
    header += '};\n'
header += '#endif\n'
(root / 'deal/native/unicode_case.h').write_text('/* ' + LICENSE + ' */\n' + header)

lua = '''-- Generated Unicode 17.0 default case data; tools/generate-unicode-case.py.
-- Unicode data: https://www.unicode.org/license.txt
local data = {}
'''
for name, mapping in [('lower', lower), ('upper', upper)]:
    lua += 'data.' + name + ' = {\n'
    for cp, values in sorted(mapping.items()):
        lua += '  [%d]={%s},\n' % (cp, ','.join(str(x) for x in values))
    lua += '}\n'
for name, prop in [('cased', 'Cased'), ('ignorable', 'Case_Ignorable')]:
    lua += 'data.' + name + ' = {' + ','.join(str(x) for pair in properties[prop] for x in pair) + '}\n'
lua += 'return data\n'
def install(path, language, body):
    path = root / path
    start = language + ' BEGIN GENERATED UNICODE CASE DATA'
    end = language + ' END GENERATED UNICODE CASE DATA'
    text = path.read_text()
    notice = '\n'.join((language + ' ' + line).rstrip() for line in LICENSE.splitlines())
    block = start + '\n' + notice + '\n' + body + '\n' + end
    text = re.sub(re.escape(start) + r'.*?' + re.escape(end) + r'\n*', '', text, flags=re.S)
    if language == '--':
        assert text.count('\nreturn __rt\n') == 1
        text = text.replace('\nreturn __rt\n', '\n' + block + '\n\nreturn __rt\n')
    else:
        text = text.rstrip() + '\n\n' + block + '\n'
    path.write_text(text)

install('deal/runtime.lua', '--', 'function __rt.string_case_data()\n' + lua + 'end')
js_data = {'lower': lower, 'upper': upper,
           'cased': [x for pair in properties['Cased'] for x in pair],
           'ignorable': [x for pair in properties['Case_Ignorable'] for x in pair]}
install('deal/runtime.js', '//', 'const $unicodeCaseData = ' + json.dumps(js_data, separators=(',', ':')) + ';')
