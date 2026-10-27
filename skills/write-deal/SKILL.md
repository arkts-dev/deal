---
name: write-deal
description: Write DEAL .deal/.d.deal source and projects using a strict TypeScript syntax subset with runtime-enforced types and distinct semantics, not JavaScript/TypeScript APIs.
---

# Write DEAL

Read project instructions and `deal.json`. Follow the bundled [language guide](references/language.md) and examples for languageVersion/stdlib 1.5. The guide links the authoritative specification online when clarification is needed. Resolve bundled links within this directory, independently of the compiler checkout.

Read the relevant rules and example before writing:

| Task | Guide | Source |
|---|---|---|
| Syntax, arithmetic | [grammar](references/language.md#syntax), [types](references/language.md#types) | [numbers](examples/src/numbers.deal) |
| Records, filtering | [classes](references/language.md#classes) | [contacts](examples/src/contacts.deal) |
| Closures, callbacks | [functions](references/language.md#functions) | [functions](examples/src/functions.deal) |
| Missing versus null | [fields](references/language.md#field-access) | [profiles](examples/src/profiles.deal) |
| Dynamic data | [tables](references/language.md#tables) | [data](examples/src/data.deal) |
| Mutation | [arrays](references/language.md#arrays) | [buffers](examples/src/buffers.deal) |
| Serialization | [JSON](references/language.md#json-serialization-jsonable) | [settings](examples/serialization/settings.deal), [caller](examples/serialization/main.deal) |
| String processing | [stdlib](references/language.md#standard-library-declarations) | [strings](examples/src/strings.deal) |
| Project wiring | [modules](references/language.md#modules) | [manifest](examples/deal.json), [entry](examples/src/main.deal) |
| Async host calls | [async](references/language.md#asyncawait), [host ABI](references/language.md#host-abi-and-interoperability) | [host binding](examples/host/store.d.deal), [client](examples/host/client.deal) |

Adapt the example; do not duplicate it into documentation. Implement requested behavior without invented APIs or placeholder success. Keep edits local. [Compile and execute](references/validation.md) through the project's execution workflow.
