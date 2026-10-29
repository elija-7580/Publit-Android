#!/usr/bin/env python3
"""Turns the iOS string catalog (Localizable.xcstrings) into one small JSON file per language
for the native Android app: app/src/main/assets/strings/<lang>.json.

Keys stay the English source text, exactly as in Swift (L10n.t("Nearby"), L10n.f("%lld min", n)),
so ported code keeps its strings. Values use Java format placeholders (%s, %d, %1$s).
Plural variations become {"one": ..., "other": ...}.

Usage: tools/strings.py <Localizable.xcstrings> <assets/strings dir>
"""
import json, os, re, sys

def java_format(s):
    s = re.sub(r'%(\d+\$)?ll?d', lambda m: '%' + (m.group(1) or '') + 'd', s)
    s = re.sub(r'%(\d+\$)?@', lambda m: '%' + (m.group(1) or '') + 's', s)
    return s

def value(loc):
    if 'stringUnit' in loc:
        return java_format(loc['stringUnit']['value'])
    plural = loc.get('variations', {}).get('plural')
    if plural:
        return {k: java_format(v['stringUnit']['value']) for k, v in plural.items() if 'stringUnit' in v}
    return None

def main(src, out):
    catalog = json.load(open(src, encoding='utf-8'))
    per_lang = {}
    for key, entry in catalog['strings'].items():
        for lang, loc in entry.get('localizations', {}).items():
            v = value(loc)
            if v is None:
                continue
            # English equal to its key (after placeholder conversion) needs no entry.
            if lang == 'en' and v == java_format(key):
                continue
            per_lang.setdefault(lang, {})[key] = v
    os.makedirs(out, exist_ok=True)
    for lang, table in per_lang.items():
        with open(os.path.join(out, lang + '.json'), 'w', encoding='utf-8') as f:
            json.dump(table, f, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
    print(f"{len(per_lang)} languages -> {out}")

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
