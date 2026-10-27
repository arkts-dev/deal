"use strict";
module.exports = {
  replaceSignature(holder, descriptor) {
    const value = holder.get("value");
    if (!value || value.$kind !== "function") return false;
    value.$sig = descriptor;
    return true;
  }
};
