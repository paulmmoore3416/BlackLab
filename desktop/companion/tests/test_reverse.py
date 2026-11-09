"""Run:  python3 -m unittest discover -s desktop/companion/tests  (no REA, Ghidra or network needed)."""
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import guards as G  # noqa: E402
import reverse as R  # noqa: E402

# Every REA 6.8 command that executes, debugs, instruments or captures a target, touches a device, installs,
# updates or reconfigures software, or writes outside the workspace. None may ever be runnable from BlackLab.
DANGEROUS = ["capture-process", "capture-electron-scenario", "capture-browser-scenario", "capture-native-ui-scenario", "capture-adb-screen",
             "debug-with-gdb", "debug-with-rizin", "observe-native-calls", "observe-javascript-runtime", "observe-web-session",
             "instrument-with-frida", "install-adb-package", "uninstall-adb-package", "uninstall", "start-adb-app", "start-adb-activity",
             "push-adb-file", "pull-adb-package", "read-adb-logcat", "environment-serve", "environment-build", "setup", "update", "mcp",
             "annotate-native-function", "decompile-all", "extract-artifact", "extract-firmware", "recover-javascript-sources",
             "cutter-command", "execute-rizin-command", "analyze-web-bundle", "export-web-scripts", "collect-adb-bugreport"]


class FakeCore:
    def __init__(self, home: Path):
        self.home = home
        self.protected = G.protected_paths(home)
        self.HOME = home

    def safe_home_path(self, p):
        return G.safe_home_path(p, self.home, self.protected)

    def is_protected(self, p):
        return G.is_protected(Path(p), self.protected)

    def current_task(self):
        return None


class AllowlistTest(unittest.TestCase):
    def test_nothing_that_runs_or_changes_things_is_allowed(self):
        for cmd in DANGEROUS:
            self.assertFalse(R.allowed(cmd), cmd)
            with self.assertRaises(ValueError):
                R.build_argv(cmd, "/tmp/x")
        for cmd in [*R.NATIVE, *R.STATIC]:
            self.assertFalse(cmd.startswith(R.DENIED_PREFIXES), cmd)

    def test_argv_shapes(self):
        self.assertEqual(["analyze", "/t/a.bin", "--provider=ghidra", "--json"], R.build_argv("analyze", "/t/a.bin"))
        self.assertEqual(["decompile", "/t/a.bin", "--procedure=0x401000", "--provider=ghidra", "--json"], R.build_argv("decompile", "/t/a.bin", "0x401000"))
        self.assertEqual(["search", "/t/a.bin", "--pattern=.", "--kind=procedures", "--mode=regex", "--provider=ghidra", "--json"],
                         R.build_argv("search", "/t/a.bin", ".", kind="procedures", regex=True))
        self.assertEqual(["inspect-elf-notes", "/t/a.bin", "--json"], R.build_argv("inspect-elf-notes", "/t/a.bin"))
        self.assertEqual(["inspect-with-objdump", "/t/a.bin", "symbols", "--json"], R.build_argv("inspect-with-objdump", "/t/a.bin", "symbols"))

    def test_values_cannot_smuggle_flags_or_bad_addresses(self):
        for bad in ("--provider=ida", "-x", "", "0x12; rm -rf ~", "a\nb"):
            with self.assertRaises(ValueError, msg=bad):
                R.build_argv("decompile", "/t/a.bin", bad)
        with self.assertRaises(ValueError):
            R.build_argv("search", "/t/a.bin", "--full-output")
        with self.assertRaises(ValueError):
            R.build_argv("inspect-with-objdump", "/t/a.bin", "disassemble_all --x")
        with self.assertRaises(ValueError):
            R.build_argv("analyze", "relative/a.bin")
        self.assertIn("--procedure=main", R.build_argv("decompile", "/t/a.bin", "main"))
        self.assertIn("--pattern=license key; expired!", R.build_argv("search", "/t/a.bin", "license key; expired!"))


class TargetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name)
        self.old = R.C, R.RE_DIR
        R.C, R.RE_DIR = FakeCore(self.home), self.home / "RE"

    def tearDown(self):
        R.C, R.RE_DIR = self.old
        self.tmp.cleanup()

    def write(self, name, data: bytes) -> Path:
        p = self.home / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        return p

    def test_kinds_from_magic_bytes(self):
        self.assertEqual("elf", R.kind_of(self.write("a", b"\x7fELF\x02\x01\x01" + b"\0" * 40)))
        self.assertEqual("kmod", R.kind_of(self.write("m.ko", b"\x7fELF" + b"\0" * 40)))
        self.assertEqual("pe", R.kind_of(self.write("w.exe", b"MZ\x90\x00" + b"\0" * 40)))
        self.assertEqual("macho", R.kind_of(self.write("m", b"\xcf\xfa\xed\xfe" + b"\0" * 40)))
        self.assertEqual("java-class", R.kind_of(self.write("A.class", b"\xca\xfe\xba\xbe" + b"\0" * 40)))
        self.assertEqual("wasm", R.kind_of(self.write("x.wasm", b"\x00asm\x01\0\0\0")))
        self.assertEqual("model", R.kind_of(self.write("x.gguf", b"GGUF\x03\0\0\0")))
        self.assertEqual("pyc", R.kind_of(self.write("x.pyc", b"\xcb\r\r\n" + b"\0" * 12)))
        apk = self.home / "renamed.zip"
        with zipfile.ZipFile(apk, "w") as z:
            z.writestr("AndroidManifest.xml", b"\x03\x00")
            z.writestr("classes.dex", b"dex\n035\0")
        self.assertEqual("apk", R.kind_of(apk))
        (self.home / "app").mkdir()
        (self.home / "app" / "package.json").write_text("{}")
        self.assertEqual("javascript", R.kind_of(self.home / "app"))
        self.assertEqual("blob", R.kind_of(self.write("x.dat", b"hello")))

    def test_targets_respect_the_path_guard(self):
        ok = self.write("Downloads/tool.bin", b"\x7fELF")
        self.assertEqual(ok.resolve(), R.resolve_target(str(ok))[0])
        self.assertEqual(ok.resolve(), R.resolve_target("tool.bin")[0], "bare names are found in ~/Downloads")
        self.assertEqual(ok.resolve(), R.resolve_target(str(self.home / "downloads" / "Tool.bin"))[0], "letter case is repaired")
        for secret in ("Desktop/BlackLab/keystore/blacklab-release.jks", ".ssh/id_ed25519", "Desktop/mining/wallet-backup/w.bin", "x/secrets.json"):
            self.write(secret, b"x")
            self.assertIsNone(R.resolve_target(str(self.home / secret))[0], secret)
        self.assertIsNone(R.resolve_target("/etc/shadow")[0])
        self.assertIsNone(R.resolve_target("")[0])
        self.assertIsNotNone(R.resolve_target("/usr/bin/env")[0], "system programs are readable")

    def test_path_in_requests(self):
        self.assertEqual("~/Downloads/a.bin", R.path_in("~/Downloads/a.bin and find the license check"))
        self.assertEqual("/home/x/My App/app", R.path_in('"/home/x/My App/app" please'))
        self.assertIsNone(R.path_in("the thing I downloaded"))


class OutputTest(unittest.TestCase):
    def test_parse_success_and_failure(self):
        ok = R.parse(json.dumps({"evidence_id": "ev", "subject": {"format": "elf", "architecture": "x86_64"}, "normalized_result": "void f(void) {}"}))
        self.assertTrue(ok["ok"])
        self.assertEqual("void f(void) {}", ok["result"])
        self.assertEqual("elf", ok["format"])
        bad = R.parse(json.dumps({"error": "Analysis failed", "code": "provider_unavailable", "message": "Ghidra is missing",
                                  "remediation": {"action": "Set GHIDRA_INSTALL_DIR"}}))
        self.assertFalse(bad["ok"])
        self.assertIn("Ghidra is missing", bad["error"])
        self.assertEqual("Set GHIDRA_INSTALL_DIR", bad["remedy"])
        self.assertFalse(R.parse("node: not found")["ok"])

    def test_render_is_compact(self):
        inv = [{"address": "0x10", "value": "Usage: %s"}, {"address": "0x20", "value": "http://x"}]
        self.assertEqual("0x10  Usage: %s\n0x20  http://x", R.render(inv))
        slim = R.render({"artifact": {"path": "/x", "sha256": "ab"}, "exports": [{"name": f"f{i}"} for i in range(40)], "build_info": None})
        self.assertNotIn("sha256", slim)
        self.assertNotIn("build_info", slim)
        self.assertIn("15 more", slim)
        self.assertTrue(R.render("x" * 5000, 100).endswith("narrow the query]"))
        self.assertFalse(R.meaningful("inspect-go-binary", {"format": "elf", "build_info": None}))
        self.assertTrue(R.meaningful("inspect-go-binary", {"build_info": {"go": "1.24"}}))

    def test_invented_code_is_flagged(self):
        real = "void FUN_00103550(void)\n{\n  fprintf(stderr,\"Usage: %s\",DAT_0010a410);\n  exit(1);\n}"
        quoted = "Here it is:\n```c\nvoid FUN_00103550(void)\n{\n  fprintf(stderr,\"Usage: %s\",DAT_0010a410);\n  exit(1);\n}\n```"
        self.assertEqual(quoted, R.check_code(quoted, [real]))
        reflowed = "```c\nfprintf(stderr,\n        \"Usage: %s\",\n        DAT_0010a410);\n```"
        self.assertEqual(reflowed, R.check_code(reflowed, [real]), "re-wrapped real code isn't flagged")
        made_up = "Simplified:\n```c\nif (strcmp(argv[optind], \"-r\") == 0) {\n    reverse = 1;\n    optind++;\n}\n```"
        self.assertIn("isn't from the decompiler", R.check_code(made_up, [real]))
        self.assertIn("isn't from the decompiler", R.check_code(made_up, []))
        self.assertEqual("No code here.", R.check_code("No code here.", []))

    def test_search_terms_fall_back_to_words(self):
        self.assertEqual(["print usage message", "usage"], R.search_terms("print usage message"))
        self.assertEqual("license key", R.search_terms("license key")[0])
        self.assertIn("https://api.example.com", R.search_terms("where does it call https://api.example.com"))

    def test_pick_strings_follows_the_goal_first(self):
        strings = [{"address": "0x1", "value": "__libc_start_main"}, {"address": "0x2", "value": "Usage: tool [opts]"}, {"address": "0x6", "value": "perror"},
                   {"address": "0x3", "value": "Your trial has expired"}, {"address": "0x4", "value": "libc.so.6"},
                   {"address": "0x5", "value": "Connecting to the update server"}]
        picks = [s["address"] for s in R.pick_strings(strings, "how does the trial expire?", imports=["perror"])]
        self.assertEqual("0x3", picks[0])
        self.assertNotIn("0x1", picks)
        self.assertNotIn("0x4", picks)
        self.assertIn("0x5", picks)
        self.assertNotIn("0x6", picks, "an import name isn't program logic")


class GuardTest(unittest.TestCase):
    def test_reverse_tool_only_for_reverse_engineering_requests(self):
        for ask in ("reverse engineer ~/Downloads/a.bin", "decompile main in the tool", "what strings are in ~/x/app.so",
                    "what does this program do", "check the apk", "ghidra status", "run rea on it",
                    "what does ~/x/xxd do with the -r option? show me the code"):
            self.assertIsNone(G.tool_guard("reverse_engineer", ask), ask)
        for ask in ("what's the weather", "add a task to call the vendor", "how much disk space is left"):
            self.assertIsNotNone(G.tool_guard("reverse_engineer", ask), ask)
        self.assertIn("reverse_engineer", G.READS_CONTENT, "binary strings are untrusted text")


if __name__ == "__main__":
    unittest.main()
