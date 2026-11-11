#!/usr/bin/env bash
# Fails if a tracked file looks like it contains personal data (the repository is public).
# Runs in CI next to the secret scan; run it locally before pushing:  scripts/privacy-scan.sh
#
# Checks every tracked text file for:
#   • real VINs — 17-character VINs whose check digit is valid (test fixtures use invalid ones on purpose)
#   • personal e-mail addresses — anything outside example.*, noreply/users.noreply.github.com
#   • personal phone numbers — anything but toll-free (8xx) lines and the 555-01xx fiction range
#   • precise GPS coordinates — "lat, lon" pairs with 4+ decimals (≈11 m); Kotlin/JS fixtures must round
#   • SSN-shaped numbers (123-45-6789)
#   • your own private strings: one per line in ~/.config/blacklab/privacy-denylist.txt (never committed),
#     e.g. your VIN, street address, plate, phone, family names, facility patient aliases. Lines starting
#     with # are comments. Matching is case-insensitive.
#
# A line can opt out with the marker  privacy-scan: allow  (use it sparingly and say why).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

denylist="${BL_PRIVACY_DENYLIST:-$HOME/.config/blacklab/privacy-denylist.txt}"
scan=$(cat <<'PY'
import os, re, sys

denylist_path = sys.argv[1]
files = [f for f in sys.stdin.buffer.read().decode().split("\0") if f]
SKIP = re.compile(r"(^|/)(scripts/privacy-scan\.sh|gradlew(\.bat)?)$|\.(png|jpe?g|webp|gif|ico|ttf|otf|woff2?|jar|apk|aab|pdf|zip|mp3|m4a|ogg|wav|mp4|onnx|tflite|bin|ppn|pv)$")
ALLOW = "privacy-scan: allow"

# --- VIN check digit (ISO 3779 / 49 CFR 565) ---
TRANS = {**{str(d): d for d in range(10)},
         **dict(zip("ABCDEFGH", range(1, 9))), **dict(zip("JKLMN", range(1, 6))), "P": 7, "R": 9,
         **dict(zip("STUVWXYZ", range(2, 10)))}
WEIGHTS = [8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2]
def vin_valid(v):
    s = sum(TRANS[c] * w for c, w in zip(v, WEIGHTS)) % 11
    return v[8] == ("X" if s == 10 else str(s))

VIN = re.compile(r"(?<![A-Z0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Z0-9])")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+\.)+[A-Za-z]{2,}")
EMAIL_OK = re.compile(r"@((.+\.)?example\.(com|org|net)|[a-z0-9.-]+\.local|users\.noreply\.github\.com|noreply\.[a-z.]+|anthropic\.com)$|^(noreply|no-reply)@|^[a-z]@[a-z]\.com$", re.I)
PHONE = re.compile(r"(?<![\d.])(?:\+?1[-. ]?)?\(?([2-9]\d{2})\)?[-. ]([2-9]\d{2})[-. ](\d{4})(?![\d])")
COORD = re.compile(r"(?<![\d.])-?\d{1,2}\.\d{4,}\s*,\s*-?\d{1,3}\.\d{4,}(?![\d])")
SSN = re.compile(r"(?<![\d-])(?!000|666|9\d\d)\d{3}-(?!00)\d{2}-(?!0000)\d{4}(?![\d-])")

deny = []
if os.path.isfile(denylist_path):
    for line in open(denylist_path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            deny.append(line.lower())

hits = []
for path in files:
    if SKIP.search(path) or not os.path.isfile(path):
        continue
    try:
        text = open(path, encoding="utf-8").read()
    except (UnicodeDecodeError, OSError):
        continue
    if len(text) > 3_000_000:  # generated bundles are checked by the tests that produce them
        continue
    for n, line in enumerate(text.splitlines(), 1):
        if ALLOW in line:
            continue
        where = f"{path}:{n}"
        for m in VIN.finditer(line):
            v = m.group(0)
            if any(c.isalpha() for c in v) and any(c.isdigit() for c in v) and vin_valid(v):
                hits.append((where, "VIN with a valid check digit", v))
        for m in EMAIL.finditer(line):
            e = m.group(0)
            local, dom = e.split("@", 1)
            if local in ("this", "super") or dom != dom.lower():  # Kotlin labels: this@GeoTag.time
                continue
            if not EMAIL_OK.search(e) and not re.match(r"^\d+@", e) and "." in e.split("@")[1]:
                local, dom = e.split("@", 1)
                if re.search(r"[a-z]", local, re.I) and not dom.lower().endswith((".kt", ".js", ".py", ".png", ".svg")):
                    hits.append((where, "personal e-mail address", e))
        for m in PHONE.finditer(line):
            area, exch, last = m.groups()
            if area.startswith("8") and area[1] == area[2]:  # 800/811(no)/822…/888 toll-free
                continue
            if area in ("800", "833", "844", "855", "866", "877", "888"):
                continue
            if exch == "555" or (area == "555"):
                continue
            hits.append((where, "phone number", m.group(0).strip()))
        for m in COORD.finditer(line):
            hits.append((where, "precise GPS coordinates", m.group(0)))
        for m in SSN.finditer(line):
            hits.append((where, "SSN-shaped number", m.group(0)))
        low = line.lower()
        for d in deny:
            if d in low:
                hits.append((where, "string from your private denylist", d[:3] + "…"))

if hits:
    for where, kind, val in hits:
        print(f"::error::{where}: {kind}: {val[:60]}")
    print(f"Privacy scan FAILED: {len(hits)} finding(s). Replace them with fake data, or mark a reviewed line "
          f"with '{ALLOW}'.")
    sys.exit(1)
extra = f", {len(deny)} private denylist entries" if deny else ""
print(f"Privacy scan passed: {len(files)} tracked files checked{extra}.")
PY
)
git ls-files -z | python3 -I -c "$scan" "$denylist"
