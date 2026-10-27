#!/usr/bin/env node
"use strict";
const fs = require("fs");
const path = require("path");
let ts;
try { ts = require("typescript"); }
catch (_) {
  // Resolve the installed tsc executable, including distro/global installations.
  const executable = (process.env.PATH || "").split(path.delimiter)
    .map(dir => path.join(dir, "tsc")).find(name => fs.existsSync(name));
  if (!executable) throw new Error("TOOL_MISSING: install the TypeScript compiler (typescript)");
  ts = require(path.resolve(path.dirname(fs.realpathSync(executable)), ".."));
}
const root = path.resolve(__dirname, "../..");
const inputPaths = process.argv.slice(2);
const defaultSelection = !inputPaths.length;
if (defaultSelection) inputPaths.push("test/ir-goldens/fixtures", "std", "skills/write-deal/examples", "docs/spec-v1.5.md", "docs/learn-deal.ts", "docs/deal-v1.5-capabilities.deal");
const sources = new Map();
// Compile-error fixtures include deliberately invalid TypeScript syntax. All
// admitted/runtime sources, companions and host declarations still participate,
// even when DEAL checking or execution currently fails. Selection uses declared
// product intent, never the compiler's current outcome.
function collectPositiveCorpus(directory) {
  for (const child of fs.readdirSync(directory).sort()) {
    const entry = path.join(directory, child);
    if (fs.statSync(entry).isDirectory()) {
      collectPositiveCorpus(entry);
    } else if (child.endsWith(".deal")) {
      const text = fs.readFileSync(entry, "utf8");
      if (entry.startsWith(path.join(root, "test/conformance/host-fixtures") + path.sep)) {
        collect(entry);
        continue;
      }
      const declarations = [...text.matchAll(/^\/\/ @expected:\s*(.*?)\s*$/gm)];
      if (declarations.length !== 1) throw new Error("Expected one corpus classification: " + entry);
      const expected = declarations[0][1];
      if (/^compile-error E\d+$/.test(expected)) continue;
      if (!/^(compile-ok|runtime-ok|runtime-error [A-Z][A-Z0-9_]*|companion|host-fixture)$/.test(expected)) {
        throw new Error("Unknown corpus classification: " + entry + ": " + expected);
      }
      collect(entry);
    }
  }
}
function collect(name) {
  const absolute = path.resolve(root, name);
  if (fs.statSync(absolute).isDirectory()) {
    for (const child of fs.readdirSync(absolute).sort()) {
      const entry = path.join(absolute, child);
      if (fs.statSync(entry).isDirectory() || /\.(deal|ts)$/.test(child)) collect(entry);
    }
  } else if (/\.(deal|ts)$/.test(absolute)) {
    sources.set(absolute + ".ts", fs.readFileSync(absolute, "utf8"));
  } else if (absolute.endsWith(".md")) {
    const text = fs.readFileSync(absolute, "utf8");
    let ordinal = 0;
    for (const match of text.matchAll(/^```(?:ts|typescript)\s*\n([\s\S]*?)^```\s*$/gm)) {
      sources.set(absolute + ".example" + (++ordinal) + ".ts", match[1]);
    }
    if (!ordinal) throw new Error("No TypeScript examples in " + name);
  }
}
for (const name of inputPaths) collect(name);
if (defaultSelection) collectPositiveCorpus(path.join(root, "test/conformance"));
if (!sources.size) throw new Error("No syntax inputs");
const options = {target: ts.ScriptTarget.ESNext, noEmit: true, noResolve: true, noLib: true};
const host = ts.createCompilerHost(options);
host.getSourceFile = (name, languageVersion) => sources.has(name)
  ? ts.createSourceFile(name, sources.get(name), languageVersion, true, ts.ScriptKind.TS) : undefined;
host.fileExists = name => sources.has(name);
host.readFile = name => sources.get(name);
const program = ts.createProgram([...sources.keys()], options, host);
// TypeScript 4.5 reports parser errors through syntactic diagnostics, but
// reports additional grammar/context errors (e.g. TS1085 octal literals and
// TS1142 throw-newline) through semantic diagnostics. Include its 1xxx
// syntax/grammar family without requiring DEAL to satisfy TS type checking
// (2xxx+ diagnostics such as unresolved names and structural assignability).
const diagnostics = [
  ...program.getSyntacticDiagnostics(),
  ...program.getSemanticDiagnostics().filter(d => d.code >= 1000 && d.code < 2000)
];
if (diagnostics.length) {
  console.error(ts.formatDiagnosticsWithColorAndContext(diagnostics, {
    getCurrentDirectory: () => root, getCanonicalFileName: name => name, getNewLine: () => "\n"
  }));
  process.exit(1);
}
console.log(`TypeScript ${ts.version}: zero syntactic/grammar diagnostics (${sources.size} inputs)`);
