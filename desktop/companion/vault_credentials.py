"""Credentials & renewals (companion 1.7): one dashboard for every certificate, card and training record.

Two sources, merged by name:
  * the phone — BlackLab › Education › Certifications syncs to `40 Learning/Certifications/<name>.md` (frontmatter
    `expires`, `status`);
  * Paul's own table in `40 Learning/🏅 Credentials.md` — under "## My credentials", one row per credential:
        | Credential | Issuer | Issued | Renew by | Evidence |
    "Renew by" takes 2028-10-31, 10/2028, "none" (never expires) or "+12m" (months after Issued). Rows here are his;
    BlackLab only rewrites the managed dashboard above them.

render() writes the dashboard (expired, due within 90 days, current, no expiry) and due_soon() feeds shift prep and
the brief. Certificate numbers and eCard codes are never needed here — keep them on the card.
"""
from __future__ import annotations

import calendar
import datetime as dt
import re

REL = "40 Learning/🏅 Credentials.md"
CERTS = "40 Learning/Certifications"
SOON_DAYS = 90
HEADER = "## My credentials"
TABLE_HEAD = "| Credential | Issuer | Issued | Renew by | Evidence |\n|---|---|---|---|---|\n"


def parse_date(s: str, end_of_month=True) -> dt.date | None:
    s = (s or "").strip()
    if not s:
        return None
    try:
        return dt.date.fromisoformat(s[:10])
    except ValueError:
        pass
    m = re.fullmatch(r"(\d{1,2})/(\d{1,2})/(\d{2,4})", s)
    if m:
        y = int(m.group(3)) + (2000 if len(m.group(3)) == 2 else 0)
        try:
            return dt.date(y, int(m.group(1)), int(m.group(2)))
        except ValueError:
            return None
    m = re.fullmatch(r"(\d{1,2})/(\d{4})", s)
    if m:
        y, mo = int(m.group(2)), int(m.group(1))
        return dt.date(y, mo, calendar.monthrange(y, mo)[1] if end_of_month else 1)
    return None


def add_months(d: dt.date, n: int) -> dt.date:
    y, m = divmod(d.month - 1 + n, 12)
    y += d.year
    return dt.date(y, m + 1, min(d.day, calendar.monthrange(y, m + 1)[1]))


def renew_date(issued: dt.date | None, renew: str) -> tuple[dt.date | None, bool]:
    """(date, never_expires)."""
    r = (renew or "").strip().lower()
    if r in ("none", "never", "n/a", "no expiry", "—", "-"):
        return None, True
    m = re.fullmatch(r"\+\s*(\d{1,3})\s*(m|mo|months?|y|yr|years?)", r)
    if m and issued:
        n = int(m.group(1)) * (12 if m.group(2).startswith("y") else 1)
        return add_months(issued, n), False
    return parse_date(renew), False


def own_rows(v) -> list[dict]:
    text = v.user_text(REL)
    if HEADER not in text:
        return []
    out = []
    for ln in text.split(HEADER, 1)[1].splitlines():
        if not ln.startswith("|") or set(ln.replace("|", "").strip()) <= set("-: "):
            continue
        cells = [c.strip() for c in ln.strip().strip("|").split("|")]
        if len(cells) < 4 or cells[0].lower() == "credential" or not cells[0]:
            continue
        issued = parse_date(cells[2])
        due, never = renew_date(issued, cells[3])
        out.append({"name": cells[0], "issuer": cells[1], "issued": issued, "renew": due, "never": never,
                    "evidence": cells[4] if len(cells) > 4 else "", "from": "table"})
    return out


def phone_rows(v) -> list[dict]:
    d = v.path(CERTS)
    out = []
    if not d.is_dir():
        return out
    for p in sorted(d.glob("*.md")):
        fm, _ = v.split_frontmatter(p.read_text(encoding="utf-8", errors="replace"))
        props = dict(re.match(r"^([a-z_]+):\s*(.*)$", ln).groups() for ln in fm if re.match(r"^([a-z_]+):\s*(.*)$", ln))
        out.append({"name": p.stem, "issuer": "", "issued": None, "renew": parse_date(props.get("expires", "").strip('"')), "never": False,
                    "evidence": f"[[{p.stem}]]", "from": "app", "status": props.get("status", "").strip('"')})
    return out


def merged(v) -> list[dict]:
    rows = {r["name"].lower(): r for r in own_rows(v)}
    for r in phone_rows(v):
        k = r["name"].lower()
        if k in rows:
            rows[k]["in_app"] = True
            rows[k]["renew"] = rows[k]["renew"] or r["renew"]
        else:
            rows[k] = dict(r, in_app=True)
    return list(rows.values())


def state(r: dict, today: dt.date) -> tuple[int, str]:
    if r.get("never"):
        return 3, "♾️ No expiry"
    if not r.get("renew"):
        return 2, "❔ Renewal date unknown"
    days = (r["renew"] - today).days
    if days < 0:
        return 0, f"🔴 Expired {-days} days ago"
    if days <= SOON_DAYS:
        return 1, f"🟡 Renew within {days} days"
    return 2, f"🟢 Current — {days // 30} months left"


def due_soon(v, today: dt.date | None = None) -> list[str]:
    today = today or v.now().date()
    out = []
    for r in merged(v):
        rank, label = state(r, today)
        if rank <= 1:
            out.append(f"{r['name']} — {label.split(' ', 1)[1].lower()}")
    return out


def render(v) -> dict:
    today = v.now().date()
    rows = merged(v)
    rows.sort(key=lambda r: (state(r, today)[0], r.get("renew") or dt.date.max, r["name"].lower()))
    lines = ["# 🏅 Credentials", "",
             "> Every certificate, card and training record, with renewals. Add or fix rows in **My credentials** below (yours —",
             "> never rewritten); certifications from BlackLab › Education show up too. \"Renew by\" can be a date, 10/2028,",
             "> +12m after issue, or none.", "",
             "| Credential | Issuer | Issued | Renew by | Status | In the app | Evidence |", "|---|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['name']} | {r.get('issuer', '')} | {r['issued'].isoformat() if r.get('issued') else '—'} | "
                     f"{r['renew'].isoformat() if r.get('renew') else ('none' if r.get('never') else '—')} | {state(r, today)[1]} | "
                     f"{'✅' if r.get('in_app') else '➕ add in Education'} | {r.get('evidence', '')} |")
    if not rows:
        lines.append("| — | | | | Add your first row below | | |")
    soon = due_soon(v, today)
    v.write_managed(REL, {"type": "credentials", "credentials": len(rows), "due_soon": len(soon), "tags": ["learning/cert"], "up": ["[[◆ Learning & Credentials]]"]},
                    "\n".join(lines), user_tail=f"\n{HEADER}\n\n{TABLE_HEAD}")
    return {"credentials": len(rows), "due_soon": soon}


def seed(v, rows: list[dict]) -> int:
    """Append rows to My credentials (skipping names already there). For one-time imports, e.g. from Drive scans."""
    p = v.path(REL)
    if not p.exists():
        render(v)
    text = p.read_text(encoding="utf-8", errors="replace")
    if HEADER not in text:
        text = text.rstrip() + f"\n\n{HEADER}\n\n{TABLE_HEAD}"
    have = {r["name"].lower() for r in own_rows(v)}
    add = [r for r in rows if r["name"].lower() not in have]
    if not add:
        return 0
    head, tail = text.split(HEADER, 1)
    tbl_lines = tail.rstrip("\n").splitlines()
    tbl_lines += [f"| {r['name']} | {r.get('issuer', '')} | {r.get('issued', '')} | {r.get('renew', '')} | {r.get('evidence', '')} |" for r in add]
    v._write(REL, head + HEADER + "\n".join(tbl_lines) + "\n")
    return len(add)
