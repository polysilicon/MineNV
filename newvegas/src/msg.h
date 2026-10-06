// Tiny JSON helpers for the link's small, flat messages (sheets/link.json).
#pragma once
#include <string>
#include <vector>

namespace msg
{
/// The value of "key" as a string ("" if missing); handles \" \\ \n \uXXXX (ASCII range).
std::string Str(const std::string &json, const char *key);
bool Bool(const std::string &json, const char *key);
/// ["a","b"] under "key".
std::vector<std::string> StrList(const std::string &json, const char *key);
/// JSON string literal for `s` (with quotes).
std::string Quote(const std::string &s);
}  // namespace msg
