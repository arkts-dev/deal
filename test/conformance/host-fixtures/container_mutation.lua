return {
  replaceFirst = function(holder, replacement)
    holder.slots[1] = replacement.value[1]
    return true
  end,
  replaceAt = function(holder, replacement, index)
    holder.slots[index + 1] = replacement.value[index + 1]
    return true
  end,
}
