"""Run:  python3 -m unittest discover -s desktop/companion/tests"""
import base64
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import terminal as T  # noqa: E402


def read_until(s, needle: bytes, since=0, timeout=10.0):
    got, nxt, end = b"", since, time.time() + timeout
    while time.time() < end:
        r = s.read(nxt, wait=1.0)
        got += base64.b64decode(r["data"])
        nxt = r["next"]
        if needle in got or r["closed"]:
            return got, nxt, r
    return got, nxt, r


class TerminalTest(unittest.TestCase):
    def test_targets_are_validated(self):
        self.assertEqual(T.parse_target("paul@homebox"), {"user": "paul", "host": "homebox", "port": 22})
        self.assertEqual(T.parse_target("ssh admin@10.0.0.5:2222"), {"user": "admin", "host": "10.0.0.5", "port": 2222})
        self.assertEqual(T.parse_target("box", default_user="paul")["user"], "paul")
        for bad in ("paul@-oProxyCommand=touch x", "paul@host;id", "paul@host:0", "paul@host:x", "Paul Moore@h", "@host", "paul@", "paul@ho st"):
            with self.assertRaises(ValueError, msg=bad):
                T.parse_target(bad)

    def test_ssh_argv_is_safe(self):
        a = T.ssh_argv({"user": "paul", "host": "homebox", "port": 22})
        self.assertEqual(a[-2:], ["--", "homebox"])            # the host can never be read as an option
        self.assertIn("StrictHostKeyChecking=accept-new", a)     # a changed key is refused by OpenSSH
        self.assertIn("ForwardAgent=no", a)
        self.assertIn("ClearAllForwardings=yes", a)
        self.assertNotIn("sh", a[:1] + a[2:])                    # never a shell on this side

    def test_hosts_list_this_computer_first(self):
        hs = T.hosts({"ssh_hosts": [{"name": "Pi", "target": "pi@raspberrypi"}, {"name": "bad", "target": "x@-oops"}]},
                     "http://100.101.102.103:8770", "homebox")
        self.assertEqual([h["id"] for h in hs], ["self", "h0"])
        self.assertEqual(hs[0]["host"], "100.101.102.103")
        self.assertEqual(hs[1]["name"], "Pi")
        self.assertEqual([h["id"] for h in T.hosts({}, "", "")], [])

    def test_only_this_computers_page(self):
        self.assertTrue(T.origin_ok(None, 8770))
        self.assertTrue(T.origin_ok("http://127.0.0.1:8770", 8770))
        for o in ("https://example.github.io", "http://127.0.0.1:9999", "http://evil.example", "null"):
            self.assertFalse(T.origin_ok(o, 8770), o)

    def test_session_streams_both_ways_and_closes(self):
        m = T.Manager(argv_for=lambda t: ["/bin/cat"])
        s = m.open({"user": "paul", "host": "h", "port": 22}, 90, 30)
        s.write(b"hello terminal\n")
        got, nxt = b"", 0
        while got.count(b"hello terminal") < 2:  # the terminal's echo, then cat's copy
            more, nxt, _ = read_until(s, b"hello terminal", since=nxt, timeout=5)
            self.assertTrue(more, "no output")
            got += more
        r = s.read(nxt, wait=0.2)               # nothing new: the long-poll returns empty
        self.assertEqual(r["data"], "")
        s.resize(120, 40)
        s.write(b"\x04")                        # Ctrl-D: cat exits
        _, _, r = read_until(s, b"\x00", since=nxt)
        self.assertTrue(r["closed"])
        with self.assertRaises(ValueError):
            s.write(b"x")
        m.close(s.id)
        with self.assertRaises(KeyError):
            m.get(s.id)

    def test_session_limit(self):
        m = T.Manager(argv_for=lambda t: ["/bin/cat"])
        opened = [m.open({"user": "u", "host": "h", "port": 22}) for _ in range(T.MAX_SESSIONS)]
        with self.assertRaises(ValueError):
            m.open({"user": "u", "host": "h", "port": 22})
        for s in opened:
            m.close(s.id)


if __name__ == "__main__":
    unittest.main()
