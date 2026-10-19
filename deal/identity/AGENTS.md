# Identity

Own immutable canonical module and class identities. A project module's public identity carries the path components to its defining file, the file name included, so one directory's same-named sibling declarations never share a public identity; an externals module preserves its raw import specifier. Keep resolution, path heuristics, descriptor rendering, and backend naming outside this package. Test structural equality and cross-module identity. Keep this file always synchronized with code.
