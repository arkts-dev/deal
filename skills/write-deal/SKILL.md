---
name: write-deal
description: Write idiomatic DEAL source, declarations, and projects. Use for .deal/.d.deal edits or translating algorithms into DEAL; do not assume JavaScript or TypeScript APIs.
---

# Write DEAL

Read project instructions and `deal.json`. Use the bundled [language guide](references/language.md) and examples. The compiler's specification for the selected languageVersion remains authoritative; obtain it when a rule is unclear or the project differs from the example manifest. Resolve bundled links within this directory.

Read the relevant rules and example before writing:

| Task | Guide | Source |
|---|---|---|
| Syntax, arithmetic | [grammar](references/language.md#syntax), [types](references/language.md#types) | [numbers](examples/src/numbers.deal) |
| Records, filtering | [classes](references/language.md#classes) | [contacts](examples/src/contacts.deal) |
| Closures, callbacks | [functions](references/language.md#functions) | [functions](examples/src/functions.deal) |
| Missing versus null | [fields](references/language.md#field-access) | [profiles](examples/src/profiles.deal) |
| Dynamic data | [tables](references/language.md#tables) | [data](examples/src/data.deal) |
| Mutation | [arrays](references/language.md#arrays), [bytes](references/language.md#bytes) | [buffers](examples/src/buffers.deal) |
| Serialization | [JSON](references/language.md#json-serialization-jsonable) | [settings](examples/serialization/settings.deal), [caller](examples/serialization/main.deal) |
| String processing | [stdlib](references/language.md#standard-library-declarations) | [strings](examples/src/strings.deal) |
| Project wiring | [modules](references/language.md#modules) | [manifest](examples/deal.json), [entry](examples/src/main.deal) |
| Async host calls | [async](references/language.md#asyncawait), [host ABI](references/language.md#host-abi-and-interoperability) | [host binding](examples/host/store.d.deal), [client](examples/host/client.deal) |
| Native calls | [C FFI](references/language.md#c-ffi-declaration-files) | Inspect the actual C header |

Adapt the example; do not duplicate it into documentation. Implement requested behavior without invented APIs or placeholder success. Keep edits local. [Compile and execute](references/validation.md) through the project's execution workflow.
