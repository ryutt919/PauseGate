"""Validate v5 release inputs and write a reproducible integrity manifest.

Inputs: Gradle test/lint reports, APK, updated documentation, and installed emulator APK.
Output: evidence/v5_20261030_release/13_manifest.json.
Run: python scripts/03_validate_release.py [--code-commit COMMIT] [--published]
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote
import xml.etree.ElementTree as ET

VERSION = "v5"
ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / "evidence/v5_20261030_release"
APK = ROOT / "build/releases/v5_20261030_release/YouSure-0.9.0.apk"
TOOLS = Path("C:/Users/kimt9/AndroidProjects/_tools")
ADB = "C:/dev/android-sdk/platform-tools/adb.exe"
CERTIFICATE = "ecb4ca12e3d212ef619d34533510fa94449fd7a532d83873333f510a4ab347c3"
DOCUMENTS = [ROOT / "mds/00_PM.md", ROOT / "Agent_handoff/00_문제_해결_기록.md",
             ROOT / "Agent_handoff/03_물결과_주기_연장.md", ROOT / "mds/공통/문서번호_대응표.md",
             ROOT / "mds/v4_개입_화면_요약_패널/00_버전_기록서.md",
             *sorted((ROOT / "mds/v5_물결_한번과_주기_연장").rglob("*.md"))]


def command(*args, env=None):
    result = subprocess.run(args, capture_output=True, check=True, env=env)
    return result.stdout.decode("utf-8").strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--code-commit")
    parser.add_argument("--published", action="store_true")
    args = parser.parse_args()
    link_count = 0
    for document in DOCUMENTS:
        text = document.read_text(encoding="utf-8")
        for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", text):
            target = unquote(target.split("#", 1)[0])
            if not target or "://" in target or target.startswith("mailto:"):
                continue
            assert (document.parent / target).exists(), f"Broken link: {document}: {target}"
            link_count += 1
    suites = [ET.parse(path).getroot() for path in
              (ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")]
    assert suites
    assert all(int(s.get("failures")) == 0 and int(s.get("errors")) == 0 for s in suites)
    count = sum(int(s.get("tests")) for s in suites)
    assert count == 45
    lint = (ROOT / "app/build/reports/lint-results-debug.txt").read_text(encoding="utf-8")
    assert "0 errors, 25 warnings" in lint
    runtime = json.loads((RUN / "00_runtime.json").read_text(encoding="utf-8"))
    assert {e["done"] for e in runtime["events"] if "done" in e} == set(range(1, 8))
    assert runtime["events"][-1]["check"] == "original data hashes and settings restored"
    digest = hashlib.sha256(APK.read_bytes()).hexdigest()
    installed = command(ADB, "-s", "emulator-5554", "shell", "pm path com.kimt9.pausegate").removeprefix("package:")
    installed_digest = command(ADB, "-s", "emulator-5554", "shell", f"sha256sum {installed}").split()[0]
    assert digest == installed_digest
    env = dict(os.environ, JAVA_HOME=str(TOOLS / "jdk/jdk-17.0.20.1+1"))
    signing = command("cmd", "/c", str(TOOLS / "sdk/build-tools/34.0.0/apksigner.bat"),
                      "verify", "--print-certs", str(APK), env=env)
    assert CERTIFICATE in signing
    manifest = {"version": VERSION, "app_version": "0.9.0", "version_code": 10,
                "code_commit": args.code_commit, "sha256": digest,
                "apk": str(APK.relative_to(ROOT)), "certificate_sha256": CERTIFICATE,
                "installed_apk_matches": True, "unit_tests": count,
                "lint_errors": 0, "lint_warnings": 25, "runtime_checks": 7,
                "relative_links_checked": link_count,
                "runtime_script": "scripts/02_release_verify.py",
                "validation_script": "scripts/03_validate_release.py",
                "release_url": "https://github.com/ryutt919/PauseGate/releases/tag/v0.9.0",
                "published": args.published,
                "limitations": ["No physical Galaxy device test", "24-hour limit covered by unit tests"]}
    (RUN / "13_manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(manifest, indent=2), flush=True)


if __name__ == "__main__":
    main()
