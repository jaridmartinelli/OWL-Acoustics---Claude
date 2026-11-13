"""Writes a short Markdown summary of JUnit results and lint issues for the run page."""
import glob, xml.etree.ElementTree as ET

print("## Tests")
tot = fail = skip = 0
failures = []
for path in glob.glob("*/build/test-results/**/*.xml", recursive=True):
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        tot += 1
        bad = case.find("failure") if case.find("failure") is not None else case.find("error")
        if bad is not None:
            fail += 1
            failures.append(f"{case.get('classname')}.{case.get('name')}: {(bad.get('message') or '').splitlines()[0][:200] if bad.get('message') else ''}")
        elif case.find("skipped") is not None:
            skip += 1
print(f"{tot} tests, {fail} failed, {skip} skipped" if tot else "No test results found (build may have failed first).")
for f in failures[:30]:
    print(f"- {f}")

print("\n## Lint")
for path in sorted(glob.glob("*/build/reports/lint-results-debug.xml")):
    issues = ET.parse(path).getroot().findall("issue")
    errs = [i for i in issues if i.get("severity") in ("Error", "Fatal")]
    print(f"- {path.split('/')[0]}: {len(errs)} errors, {len(issues) - len(errs)} warnings")
    for i in errs[:15]:
        loc = i.find("location")
        where = f"{loc.get('file','').split('/src/')[-1]}:{loc.get('line','?')}" if loc is not None else ""
        print(f"  - {i.get('id')} {where}: {i.get('message','')[:160]}")

print("\n## Screenshots")
shots = sorted(glob.glob("lab/build/trace-stills/*.png"))
print(f"{len(shots)} rendered trace-screen stills in the `screenshots` artifact." if shots else "None rendered.")
