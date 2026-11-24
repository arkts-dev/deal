# Compiler diagnostic protocol — JSON v3 proposal

## Semantics

Responses use minified UTF-8 JSON.

### Outcomes

`accepted` means the requested operation completed successfully; `rejected` means a source or amendment contract prevented acceptance; `failed` means an operational failure prevented completion.

### Snapshots and coordinates

The tuple `(file, revision, snapshot)` identifies a source snapshot; `revision` identifies the exact source text.

Paths within the invocation's project root are root-relative; external source paths are absolute.

File entries follow first-reference order and share an index wherever their identifying tuple recurs.

Array references are zero-based and scoped to the containing response.

A location's `at` contains start line, start column, end line and end column; lines and UTF-16 columns are one-based, and the end is exclusive.

`offset` contains zero-based UTF-16 start and exclusive-end offsets.

Omitted `origin` denotes a source location; a synthetic anchor identifies a producer-chosen explanatory location.

### Occurrences and evidence

The diagnostic array preserves every occurrence and its order from the producer's result; each index identifies one occurrence within the response.

Distinct source occurrences retain distinct entries, and `cause` identifies a producer-established causal occurrence.

Optional fields carry available producer observations; absence denotes unavailable or inapplicable evidence, and supplied zeroes, false values and empty edit strings retain their meanings.

Messages explain rejected contracts; guidance carries actionable advice and the semantic caveats needed to choose an intentional repair.

Responsibility identifies the contract to inspect.

Evidence comes from retained producer observations associated with the diagnostic occurrence.

Alternatives retain producer ranking order; `omitted` counts additional observed entries in a bounded collection.

A mismatch inherits the diagnostic's `expected` and `actual` wherever its corresponding fields are omitted; its path follows type components, while the evidence's path follows declared fields and array elements.

`argumentIndex` selects a zero-based argument; `-1` denotes a call-wide failure.

A type component's `index` selects the zero-based function parameter.

Semantic owner and related identifiers name retained compiler entities; `operationIndex` selects an operation in the submitted amendment batch.

### Source context and edits

An excerpt's `start` is its zero-based UTF-16 source offset; identical `(file, start, text)` excerpts share one entry.

`primaryTruncated` records partial coverage of the primary range.

An edit replaces the interval selected by `offset` in the snapshot selected by `file`; `expected` is the interval's existing text and `text` is the replacement.

An empty interval expresses insertion; an empty replacement expresses deletion.

Application verifies the snapshot revision and expected text before mutation.

### Repair integration

Slots reference the shared diagnostic array through their `diagnostics` indexes.

A repair response's `source` and `sourceDigest` identify the committed source; candidate diagnostic coordinates refer to the snapshot selected by their file entry.

## Schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "Compiler diagnostic protocol v3",
  "oneOf": [
    {
      "$ref": "#/$defs/compilerResponse"
    },
    {
      "$ref": "#/$defs/repairResponse"
    }
  ],
  "$defs": {
    "offset": {
      "type": "array",
      "prefixItems": [
        {
          "type": "integer",
          "minimum": 0
        },
        {
          "type": "integer",
          "minimum": 0
        }
      ],
      "items": false,
      "minItems": 2,
      "maxItems": 2
    },
    "location": {
      "type": "object",
      "properties": {
        "file": {
          "type": "integer",
          "minimum": 0
        },
        "at": {
          "type": "array",
          "prefixItems": [
            {
              "type": "integer",
              "minimum": 1
            },
            {
              "type": "integer",
              "minimum": 1
            },
            {
              "type": "integer",
              "minimum": 1
            },
            {
              "type": "integer",
              "minimum": 1
            }
          ],
          "items": false,
          "minItems": 4,
          "maxItems": 4
        },
        "offset": {
          "$ref": "#/$defs/offset"
        },
        "origin": {
          "const": "synthetic"
        }
      },
      "required": [
        "file",
        "at"
      ],
      "additionalProperties": false
    },
    "file": {
      "type": "object",
      "properties": {
        "file": {
          "type": "string",
          "minLength": 1
        },
        "revision": {
          "type": "string",
          "minLength": 1
        },
        "snapshot": {
          "enum": [
            "current",
            "candidate"
          ]
        }
      },
      "required": [
        "file",
        "revision",
        "snapshot"
      ],
      "additionalProperties": false
    },
    "excerpt": {
      "type": "object",
      "properties": {
        "file": {
          "type": "integer",
          "minimum": 0
        },
        "start": {
          "type": "integer",
          "minimum": 0
        },
        "text": {
          "type": "string"
        },
        "primaryTruncated": {
          "const": true
        }
      },
      "required": [
        "file",
        "start",
        "text"
      ],
      "additionalProperties": false
    },
    "note": {
      "type": "object",
      "properties": {
        "message": {
          "type": "string",
          "minLength": 1
        },
        "range": {
          "$ref": "#/$defs/location"
        }
      },
      "required": [
        "message"
      ],
      "additionalProperties": false
    },
    "edit": {
      "type": "object",
      "properties": {
        "file": {
          "type": "integer",
          "minimum": 0
        },
        "offset": {
          "$ref": "#/$defs/offset"
        },
        "expected": {
          "type": "string"
        },
        "text": {
          "type": "string"
        }
      },
      "required": [
        "file",
        "offset",
        "expected",
        "text"
      ],
      "additionalProperties": false
    },
    "difference": {
      "type": "object",
      "properties": {
        "reference": {
          "enum": [
            "TYPESCRIPT"
          ]
        },
        "kind": {
          "enum": [
            "UNSUPPORTED_SYNTAX",
            "UNSUPPORTED_API",
            "DIFFERENT_SEMANTICS"
          ]
        },
        "feature": {
          "type": "string",
          "pattern": "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$"
        }
      },
      "required": [
        "reference",
        "kind",
        "feature"
      ],
      "additionalProperties": false
    },
    "pathSegment": {
      "oneOf": [
        {
          "type": "object",
          "properties": {
            "kind": {
              "const": "FIELD"
            },
            "name": {
              "type": "string",
              "minLength": 1
            }
          },
          "required": [
            "kind",
            "name"
          ],
          "additionalProperties": false
        },
        {
          "type": "object",
          "properties": {
            "kind": {
              "const": "ARRAY_ELEMENT"
            }
          },
          "required": [
            "kind"
          ],
          "additionalProperties": false
        }
      ]
    },
    "typeComponent": {
      "oneOf": [
        {
          "type": "object",
          "properties": {
            "kind": {
              "enum": [
                "ARRAY_ELEMENT",
                "NULLABLE_INNER",
                "FUNCTION_RESULT"
              ]
            }
          },
          "required": [
            "kind"
          ],
          "additionalProperties": false
        },
        {
          "type": "object",
          "properties": {
            "kind": {
              "const": "FUNCTION_PARAMETER"
            },
            "index": {
              "type": "integer",
              "minimum": 0
            }
          },
          "required": [
            "kind",
            "index"
          ],
          "additionalProperties": false
        }
      ]
    },
    "mismatch": {
      "type": "object",
      "properties": {
        "rule": {
          "type": "string",
          "minLength": 1
        },
        "path": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/typeComponent"
          },
          "minItems": 1
        },
        "expected": {
          "type": "string",
          "minLength": 1
        },
        "actual": {
          "type": "string",
          "minLength": 1
        },
        "truncated": {
          "const": true
        }
      },
      "required": [
        "rule"
      ],
      "additionalProperties": false
    },
    "call": {
      "type": "object",
      "properties": {
        "callee": {
          "type": "string",
          "minLength": 1
        },
        "signature": {
          "type": "string",
          "minLength": 1
        },
        "declarationRange": {
          "$ref": "#/$defs/location"
        },
        "argumentIndex": {
          "type": "integer",
          "minimum": -1
        },
        "parameterName": {
          "type": "string",
          "minLength": 1
        },
        "expectedCount": {
          "type": "integer",
          "minimum": 0
        },
        "actualCount": {
          "type": "integer",
          "minimum": 0
        },
        "parameterRange": {
          "$ref": "#/$defs/location"
        }
      },
      "required": [
        "callee",
        "signature",
        "argumentIndex",
        "expectedCount",
        "actualCount"
      ],
      "additionalProperties": false
    },
    "operand": {
      "type": "object",
      "properties": {
        "role": {
          "type": "string",
          "minLength": 1
        },
        "type": {
          "type": "string",
          "minLength": 1
        },
        "range": {
          "$ref": "#/$defs/location"
        }
      },
      "required": [
        "role",
        "type"
      ],
      "additionalProperties": false
    },
    "operator": {
      "type": "object",
      "properties": {
        "token": {
          "type": "string",
          "minLength": 1
        },
        "operands": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/operand"
          },
          "minItems": 1
        },
        "acceptedCombinations": {
          "type": "array",
          "items": {
            "type": "string",
            "minLength": 1
          },
          "minItems": 1
        }
      },
      "required": [
        "token",
        "operands"
      ],
      "additionalProperties": false
    },
    "syntax": {
      "type": "object",
      "properties": {
        "context": {
          "type": "string",
          "minLength": 1
        },
        "expectedKinds": {
          "type": "array",
          "items": {
            "type": "string",
            "minLength": 1
          },
          "minItems": 1
        },
        "foundKind": {
          "type": "string",
          "minLength": 1
        },
        "lexeme": {
          "type": "string"
        },
        "truncated": {
          "const": true
        }
      },
      "required": [
        "context",
        "foundKind"
      ],
      "additionalProperties": false
    },
    "candidate": {
      "type": "object",
      "properties": {
        "name": {
          "type": "string",
          "minLength": 1
        },
        "kind": {
          "type": "string",
          "minLength": 1
        },
        "type": {
          "type": "string",
          "minLength": 1
        },
        "declarationRange": {
          "$ref": "#/$defs/location"
        }
      },
      "required": [
        "name",
        "kind",
        "type"
      ],
      "additionalProperties": false
    },
    "observation": {
      "type": "object",
      "properties": {
        "name": {
          "type": "string",
          "minLength": 1
        },
        "kind": {
          "type": "string",
          "minLength": 1
        },
        "status": {
          "enum": [
            "NOT_FOUND_IN_LEXICAL_OR_IMPORTED_SURFACES",
            "DECLARED_LATER",
            "OUT_OF_SCOPE",
            "IMPORTED_MEMBER",
            "WRONG_NAMESPACE"
          ]
        },
        "declarationRange": {
          "$ref": "#/$defs/location"
        }
      },
      "required": [
        "name",
        "kind",
        "status"
      ],
      "additionalProperties": false
    },
    "candidates": {
      "type": "object",
      "properties": {
        "items": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/candidate"
          }
        },
        "omitted": {
          "type": "integer",
          "minimum": 1
        }
      },
      "required": [
        "items"
      ],
      "additionalProperties": false
    },
    "observations": {
      "type": "object",
      "properties": {
        "items": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/observation"
          }
        },
        "omitted": {
          "type": "integer",
          "minimum": 1
        }
      },
      "required": [
        "items"
      ],
      "additionalProperties": false
    },
    "names": {
      "type": "object",
      "properties": {
        "candidates": {
          "$ref": "#/$defs/candidates"
        },
        "observations": {
          "$ref": "#/$defs/observations"
        }
      },
      "required": [],
      "additionalProperties": false
    },
    "construction": {
      "type": "object",
      "properties": {
        "className": {
          "type": "string",
          "minLength": 1
        },
        "provider": {
          "type": "string"
        },
        "declarationRange": {
          "$ref": "#/$defs/location"
        },
        "fieldName": {
          "type": "string",
          "minLength": 1
        },
        "optional": {
          "type": "boolean"
        },
        "fieldRange": {
          "$ref": "#/$defs/location"
        },
        "annotationRange": {
          "$ref": "#/$defs/location"
        }
      },
      "required": [
        "className",
        "provider"
      ],
      "additionalProperties": false
    },
    "strings": {
      "type": "object",
      "properties": {
        "items": {
          "type": "array",
          "items": {
            "type": "string",
            "minLength": 1
          }
        },
        "omitted": {
          "type": "integer",
          "minimum": 1
        }
      },
      "required": [
        "items"
      ],
      "additionalProperties": false
    },
    "exports": {
      "type": "object",
      "properties": {
        "alias": {
          "type": "string",
          "minLength": 1
        },
        "requested": {
          "type": "string",
          "minLength": 1
        },
        "available": {
          "$ref": "#/$defs/strings"
        },
        "importRange": {
          "$ref": "#/$defs/location"
        }
      },
      "required": [
        "alias",
        "requested",
        "available"
      ],
      "additionalProperties": false
    },
    "resolution": {
      "type": "object",
      "properties": {
        "specifier": {
          "type": "string",
          "minLength": 1
        },
        "mode": {
          "type": "string",
          "minLength": 1
        },
        "attempted": {
          "$ref": "#/$defs/strings"
        }
      },
      "required": [
        "specifier",
        "mode",
        "attempted"
      ],
      "additionalProperties": false
    },
    "edge": {
      "type": "object",
      "properties": {
        "from": {
          "type": "string",
          "minLength": 1
        },
        "to": {
          "type": "string",
          "minLength": 1
        },
        "importRange": {
          "$ref": "#/$defs/location"
        }
      },
      "required": [
        "from",
        "to"
      ],
      "additionalProperties": false
    },
    "cycle": {
      "type": "object",
      "properties": {
        "edges": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/edge"
          },
          "minItems": 1
        },
        "omitted": {
          "type": "integer",
          "minimum": 1
        }
      },
      "required": [
        "edges"
      ],
      "additionalProperties": false
    },
    "invalidation": {
      "type": "object",
      "properties": {
        "reason": {
          "type": "string",
          "minLength": 1
        },
        "operation": {
          "type": "string",
          "minLength": 1
        },
        "range": {
          "$ref": "#/$defs/location"
        },
        "previous": {
          "type": "string",
          "minLength": 1
        }
      },
      "required": [
        "reason",
        "operation",
        "range",
        "previous"
      ],
      "additionalProperties": false
    },
    "missingSymbol": {
      "type": "object",
      "properties": {
        "kind": {
          "type": "string",
          "minLength": 1
        },
        "name": {
          "type": "string",
          "minLength": 1
        },
        "namespace": {
          "type": "string"
        },
        "constraints": {
          "type": "array",
          "items": {
            "type": "string",
            "minLength": 1
          },
          "minItems": 1
        }
      },
      "required": [
        "kind",
        "name"
      ],
      "additionalProperties": false
    },
    "evidence": {
      "type": "object",
      "properties": {
        "path": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/pathSegment"
          },
          "minItems": 1
        },
        "mismatch": {
          "$ref": "#/$defs/mismatch"
        },
        "call": {
          "$ref": "#/$defs/call"
        },
        "operator": {
          "$ref": "#/$defs/operator"
        },
        "syntax": {
          "$ref": "#/$defs/syntax"
        },
        "names": {
          "$ref": "#/$defs/names"
        },
        "construction": {
          "$ref": "#/$defs/construction"
        },
        "exports": {
          "$ref": "#/$defs/exports"
        },
        "resolution": {
          "$ref": "#/$defs/resolution"
        },
        "cycle": {
          "$ref": "#/$defs/cycle"
        },
        "invalidation": {
          "$ref": "#/$defs/invalidation"
        },
        "missingSymbols": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/missingSymbol"
          },
          "minItems": 1
        }
      },
      "required": [],
      "additionalProperties": false,
      "minProperties": 1
    },
    "repairScope": {
      "type": "object",
      "properties": {
        "operation": {
          "type": "string",
          "minLength": 1
        },
        "owner": {
          "type": "string",
          "minLength": 1
        }
      },
      "required": [
        "operation",
        "owner"
      ],
      "additionalProperties": false
    },
    "responsibility": {
      "enum": [
        "SOURCE_CONTRACT",
        "CONFIGURATION_CONTRACT",
        "HOST_CONTRACT",
        "COMPILER_CONTRACT",
        "RUNTIME_CONTRACT",
        "PROTOCOL_CONTRACT"
      ]
    },
    "diagnostic": {
      "type": "object",
      "properties": {
        "code": {
          "type": "string",
          "minLength": 1
        },
        "severity": {
          "enum": [
            "error",
            "warning"
          ]
        },
        "message": {
          "type": "string",
          "minLength": 1
        },
        "range": {
          "$ref": "#/$defs/location"
        },
        "responsibility": {
          "$ref": "#/$defs/responsibility"
        },
        "reason": {
          "type": "string",
          "minLength": 1
        },
        "operation": {
          "type": "string",
          "minLength": 1
        },
        "expected": {
          "type": "string",
          "minLength": 1
        },
        "actual": {
          "type": "string",
          "minLength": 1
        },
        "guidance": {
          "type": "array",
          "items": {
            "type": "string",
            "minLength": 1
          },
          "minItems": 1
        },
        "evidence": {
          "$ref": "#/$defs/evidence"
        },
        "difference": {
          "$ref": "#/$defs/difference"
        },
        "notes": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/note"
          },
          "minItems": 1
        },
        "context": {
          "type": "integer",
          "minimum": 0
        },
        "edit": {
          "$ref": "#/$defs/edit"
        },
        "owner": {
          "type": "string",
          "minLength": 1
        },
        "related": {
          "type": "array",
          "items": {
            "type": "string",
            "minLength": 1
          },
          "minItems": 1
        },
        "repairs": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/repairScope"
          },
          "minItems": 1
        },
        "operationIndex": {
          "type": "integer",
          "minimum": 0
        },
        "cause": {
          "type": "integer",
          "minimum": 0
        }
      },
      "required": [
        "code",
        "severity",
        "message",
        "responsibility"
      ],
      "additionalProperties": false
    },
    "failure": {
      "type": "object",
      "properties": {
        "reason": {
          "type": "string",
          "minLength": 1
        },
        "responsibility": {
          "$ref": "#/$defs/responsibility"
        },
        "message": {
          "type": "string",
          "minLength": 1
        }
      },
      "required": [
        "reason",
        "responsibility",
        "message"
      ],
      "additionalProperties": false
    },
    "report": {
      "type": "object",
      "properties": {
        "version": {
          "const": 3
        },
        "status": {
          "enum": [
            "accepted",
            "rejected",
            "failed"
          ]
        },
        "files": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/file"
          }
        },
        "diagnostics": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/diagnostic"
          }
        },
        "context": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/excerpt"
          },
          "minItems": 1
        },
        "failures": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/failure"
          },
          "minItems": 1
        }
      },
      "required": [
        "version",
        "status",
        "files",
        "diagnostics"
      ],
      "allOf": [
        {
          "if": {
            "properties": {
              "status": {
                "const": "failed"
              }
            }
          },
          "then": {
            "required": [
              "failures"
            ]
          },
          "else": {
            "properties": {
              "failures": false
            }
          }
        }
      ]
    },
    "compilerResponse": {
      "allOf": [
        {
          "$ref": "#/$defs/report"
        }
      ],
      "unevaluatedProperties": false
    },
    "precondition": {
      "type": "object",
      "properties": {
        "baseDigest": {
          "type": "string",
          "minLength": 1
        },
        "expectedTargetFingerprints": {
          "type": "object",
          "additionalProperties": {
            "type": "string",
            "minLength": 1
          }
        }
      },
      "required": [
        "baseDigest",
        "expectedTargetFingerprints"
      ],
      "additionalProperties": false
    },
    "slot": {
      "type": "object",
      "properties": {
        "slotId": {
          "type": "string",
          "minLength": 1
        },
        "operation": {
          "type": "string",
          "minLength": 1
        },
        "targetId": {
          "type": "string",
          "minLength": 1
        },
        "targetFingerprint": {
          "type": "string",
          "minLength": 1
        },
        "payload": {
          "type": "object",
          "additionalProperties": {
            "type": "string"
          }
        },
        "payloadFingerprint": {
          "type": "string",
          "minLength": 1
        },
        "status": {
          "enum": [
            "SEALED",
            "STAGED",
            "REJECTED",
            "BLOCKED",
            "COMMIT_READY"
          ]
        },
        "dependencyGroupId": {
          "type": "string",
          "minLength": 1
        },
        "diagnostics": {
          "type": "array",
          "items": {
            "type": "integer",
            "minimum": 0
          }
        }
      },
      "required": [
        "slotId",
        "operation",
        "targetId",
        "targetFingerprint",
        "payload",
        "payloadFingerprint",
        "status",
        "dependencyGroupId",
        "diagnostics"
      ],
      "additionalProperties": false
    },
    "group": {
      "type": "object",
      "properties": {
        "groupId": {
          "type": "string",
          "minLength": 1
        },
        "dependsOn": {
          "type": "array",
          "items": {
            "type": "string",
            "minLength": 1
          }
        },
        "status": {
          "enum": [
            "REPAIR_REQUIRED",
            "COMMIT_READY",
            "BLOCKED",
            "STAGED",
            "SEALED"
          ]
        }
      },
      "required": [
        "groupId",
        "dependsOn",
        "status"
      ],
      "additionalProperties": false
    },
    "workspace": {
      "type": "object",
      "properties": {
        "workspaceId": {
          "type": "string",
          "minLength": 1
        },
        "workspaceDigest": {
          "type": "string",
          "minLength": 1
        },
        "baseRevision": {
          "type": "object",
          "properties": {
            "sourceDigest": {
              "type": "string",
              "minLength": 1
            }
          },
          "required": [
            "sourceDigest"
          ],
          "additionalProperties": false
        },
        "inspectionDigest": {
          "type": "string",
          "minLength": 1
        },
        "precondition": {
          "$ref": "#/$defs/precondition"
        },
        "slots": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/slot"
          }
        },
        "groups": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/group"
          }
        },
        "repairRound": {
          "type": "integer",
          "minimum": 0
        }
      },
      "required": [
        "workspaceId",
        "workspaceDigest",
        "baseRevision",
        "inspectionDigest",
        "precondition",
        "slots",
        "groups",
        "repairRound"
      ],
      "additionalProperties": false
    },
    "repairResponse": {
      "allOf": [
        {
          "$ref": "#/$defs/report"
        },
        {
          "type": "object",
          "properties": {
            "source": {
              "type": "string"
            },
            "sourceDigest": {
              "type": "string",
              "minLength": 1
            },
            "workspace": {
              "$ref": "#/$defs/workspace"
            }
          },
          "required": [
            "source",
            "sourceDigest",
            "workspace"
          ]
        }
      ],
      "unevaluatedProperties": false
    }
  }
}
```

## Samples

### Compiler rejection

```json
{
  "version": 3,
  "status": "rejected",
  "files": [
    {
      "file": "main.deal",
      "revision": "revision-current",
      "snapshot": "current"
    }
  ],
  "diagnostics": [
    {
      "code": "E5003",
      "severity": "error",
      "message": "Return type mismatch: expected string, got string | undefined",
      "range": {
        "file": 0,
        "at": [
          1,
          60,
          1,
          65
        ],
        "offset": [
          59,
          64
        ]
      },
      "responsibility": "SOURCE_CONTRACT",
      "reason": "return.incompatible",
      "operation": "return",
      "expected": "string",
      "actual": "string | undefined",
      "guidance": [
        "Guard undefined before returning the value."
      ],
      "evidence": {
        "mismatch": {
          "rule": "type.exact"
        }
      },
      "notes": [
        {
          "message": "Declared result annotation",
          "range": {
            "file": 0,
            "at": [
              1,
              46,
              1,
              52
            ],
            "offset": [
              45,
              51
            ]
          }
        }
      ],
      "context": 0
    }
  ],
  "context": [
    {
      "file": 0,
      "start": 0,
      "text": "export function text(value:string|undefined):string{return value;}\n"
    }
  ]
}
```

### Repair rejection

```json
{
  "version": 3,
  "status": "rejected",
  "files": [
    {
      "file": "main.deal",
      "revision": "revision-candidate",
      "snapshot": "candidate"
    }
  ],
  "diagnostics": [
    {
      "code": "E5003",
      "severity": "error",
      "message": "Return type mismatch: expected number, got string",
      "range": {
        "file": 0,
        "at": [
          2,
          32,
          2,
          35
        ],
        "offset": [
          61,
          64
        ]
      },
      "responsibility": "SOURCE_CONTRACT",
      "reason": "return.incompatible",
      "operation": "return",
      "expected": "number",
      "actual": "string",
      "guidance": [
        "Return a number matching the declared result contract."
      ],
      "notes": [
        {
          "message": "Declared result annotation",
          "range": {
            "file": 0,
            "at": [
              2,
              18,
              2,
              24
            ],
            "offset": [
              47,
              53
            ]
          }
        }
      ],
      "owner": "deal:module:main.deal",
      "repairs": [
        {
          "operation": "addDeclaration",
          "owner": "deal:module:main.deal"
        }
      ],
      "context": 0
    }
  ],
  "context": [
    {
      "file": 0,
      "start": 0,
      "text": "export function main():void{}\nfunction count():number{return \"x\";}\n"
    }
  ],
  "source": "export function main():void{}\n",
  "sourceDigest": "revision-current",
  "workspace": {
    "workspaceId": "workspace-example",
    "workspaceDigest": "workspace-rejected",
    "baseRevision": {
      "sourceDigest": "revision-current"
    },
    "inspectionDigest": "inspection-example",
    "precondition": {
      "baseDigest": "revision-current",
      "expectedTargetFingerprints": {
        "deal:module:main.deal": "target-example"
      }
    },
    "slots": [
      {
        "slotId": "slot-example",
        "operation": "addDeclaration",
        "targetId": "deal:module:main.deal",
        "targetFingerprint": "target-example",
        "payload": {
          "declaration": "function count():number{return \"x\";}\n"
        },
        "payloadFingerprint": "payload-example",
        "status": "REJECTED",
        "dependencyGroupId": "G1",
        "diagnostics": [
          0
        ]
      }
    ],
    "groups": [
      {
        "groupId": "G1",
        "dependsOn": [],
        "status": "REPAIR_REQUIRED"
      }
    ],
    "repairRound": 0
  }
}
```
