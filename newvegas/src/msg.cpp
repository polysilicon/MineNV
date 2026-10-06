#include "msg.h"
#include <cstdio>
#include <cstdlib>

namespace
{
size_t ValueStart(const std::string &json, const char *key)
{
	std::string k = std::string("\"") + key + "\"";
	size_t p = json.find(k);
	if (p == std::string::npos)
		return p;
	p = json.find(':', p + k.size());
	if (p == std::string::npos)
		return p;
	p++;
	while (p < json.size() && (json[p] == ' ' || json[p] == '\t'))
		p++;
	return p;
}

// json[p] is the opening quote; returns the decoded string and moves p past the closing quote.
std::string ReadString(const std::string &json, size_t &p)
{
	std::string out;
	for (p++; p < json.size() && json[p] != '"'; p++)
	{
		char c = json[p];
		if (c == '\\' && p + 1 < json.size())
		{
			char e = json[++p];
			switch (e)
			{
			case 'n': out += '\n'; break;
			case 't': out += '\t'; break;
			case 'u':
				if (p + 4 < json.size())
				{
					unsigned v = std::strtoul(json.substr(p + 1, 4).c_str(), nullptr, 16);
					out += v < 0x80 ? char(v) : '?';
					p += 4;
				}
				break;
			default: out += e;
			}
		}
		else
			out += c;
	}
	p++;
	return out;
}
}  // namespace

namespace msg
{
std::string Str(const std::string &json, const char *key)
{
	size_t p = ValueStart(json, key);
	return p != std::string::npos && p < json.size() && json[p] == '"' ? ReadString(json, p) : std::string();
}

bool Bool(const std::string &json, const char *key)
{
	size_t p = ValueStart(json, key);
	return p != std::string::npos && json.compare(p, 4, "true") == 0;
}

std::vector<std::string> StrList(const std::string &json, const char *key)
{
	std::vector<std::string> out;
	size_t p = ValueStart(json, key);
	if (p == std::string::npos || p >= json.size() || json[p] != '[')
		return out;
	for (p++; p < json.size() && json[p] != ']';)
	{
		if (json[p] == '"')
			out.push_back(ReadString(json, p));
		else
			p++;
	}
	return out;
}

std::string Quote(const std::string &s)
{
	std::string out = "\"";
	for (unsigned char c : s)
	{
		if (c == '"' || c == '\\')
		{
			out += '\\';
			out += char(c);
		}
		else if (c < 0x20)
		{
			char buf[8];
			std::snprintf(buf, sizeof buf, "\\u%04x", c);
			out += buf;
		}
		else
			out += char(c);
	}
	return out + "\"";
}
}  // namespace msg
