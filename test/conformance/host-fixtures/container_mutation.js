"use strict";
module.exports = {
  replaceFirst(holder, replacement) {
    const slots = holder.get("slots");
    const value = replacement.get("value");
    slots[0] = require("../deal/runtime").arrayAt(value, 0);
    return true;
  },
  replaceAt(holder, replacement, index) {
    const slots = holder.get("slots");
    slots[index] = require("../deal/runtime").arrayAt(replacement.get("value"), index);
    return true;
  },
};
