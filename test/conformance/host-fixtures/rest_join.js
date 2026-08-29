"use strict";

// Host fixture implementation for the host-rest-ok/host-rest-bad
// conformance tests: the v1.2 fixed-array parameter form (DEAL v1.2
// removed rest parameters — the join export takes a fixed array
// parameter, never JS rest syntax). The boundary wrapper checks the
// array against the declared string[] element type.
module.exports = {
  join: function (sep, parts) {
    return parts.join(sep);
  },
};
