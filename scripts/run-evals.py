#!/usr/bin/env python3
"""Run the routing eval suite on any supported agent CLI.

Claude runs delegate to `claude plugin eval` (the official harness). Kimi and
Codex runs drive each CLI's headless mode and grade the event stream here:
`tool_used` graders count Skill invocations matching input_match, `regex`
graders match the final answer text. No LLM judges, no ablation arms.
"""
import argparse
import fnmatch
import json
import re
import select
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EVALS = ROOT / "evals"
RESULTS = EVALS / "results"

FLAG_MAP = {"i": re.IGNORECASE, "m": re.MULTILINE, "s": re.DOTALL, "x": re.VERBOSE}


def parse_frontmatter(text):
    """Tiny YAML-subset parser: scalars, inline lists, quoted strings."""
    if not text.startswith("---"):
        return {}, text
    _, fm, body = text.split("---", 2)
    data = {}
    for line in fm.strip().splitlines():
        if ":" not in line:
            continue
        key, _, raw = line.partition(":")
        raw = raw.strip()
        if raw.startswith("[") and raw.endswith("]"):
            value = [v.strip().strip("'\"") for v in raw[1:-1].split(",") if v.strip()]
        elif len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in "'\"":
            value = raw[1:-1]
        else:
            try:
                value = int(raw)
            except ValueError:
                try:
                    value = float(raw)
                except ValueError:
                    value = raw
        data[key.strip()] = value
    return data, body.strip()


def load_cases(case_globs):
    cases = []
    for prompt in sorted(EVALS.glob("*/prompt.md")):
        name = prompt.parent.name
        if case_globs and not any(fnmatch.fnmatch(name, g) for g in case_globs):
            continue
        meta, prompt_text = parse_frontmatter(prompt.read_text())
        graders = []
        for g in sorted((prompt.parent / "graders").glob("*.md")):
            gmeta, _ = parse_frontmatter(g.read_text())
            gmeta["name"] = g.stem
            graders.append(gmeta)
        cases.append({"name": name, "prompt": prompt_text, "meta": meta, "graders": graders})
    return cases


def walk(obj):
    """Yield every dict anywhere in a parsed JSON event."""
    if isinstance(obj, dict):
        yield obj
        for v in obj.values():
            yield from walk(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from walk(v)


def parse_event_stream(stdout):
    """Extract Skill invocations and assistant text from stream-json output."""
    skill_inputs = []
    texts = []
    for line in stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        for node in walk(event):
            name = node.get("name") or node.get("tool_name")
            is_tool = node.get("type") in ("tool_use", "tool_call") or "arguments" in node or "input" in node
            if name == "Skill" and is_tool:
                payload = node.get("input") or node.get("arguments") or {}
                if isinstance(payload, str):
                    try:
                        payload = json.loads(payload)
                    except json.JSONDecodeError:
                        payload = {"raw": payload}
                skill_inputs.append(payload)
            if node.get("type") == "text" and isinstance(node.get("text"), str):
                texts.append(node["text"])
            if node.get("role") == "assistant" and isinstance(node.get("content"), str):
                texts.append(node["content"])
        if event.get("type") == "result" and isinstance(event.get("result"), str):
            texts.append(event["result"])
    return skill_inputs, "\n".join(texts)


def grade(graders, skill_inputs, final_text):
    results = []
    serialized = [json.dumps(i) for i in skill_inputs]
    for g in graders:
        gtype = g.get("type")
        if gtype == "tool_used":
            match = g.get("input_match")
            pattern = re.compile(match) if match else None
            count = sum(1 for s in serialized if pattern is None or pattern.search(s))
            lo = g.get("min", 1)
            hi = g.get("max")
            passed = count >= lo and (hi is None or count <= hi)
            detail = f"Skill matched {count}x (expected {lo}..{hi if hi is not None else 'inf'})"
        elif gtype == "regex":
            flags = 0
            for ch in g.get("flags", ""):
                flags |= FLAG_MAP.get(ch, 0)
            passed = bool(re.search(g["pattern"], final_text, flags))
            detail = "pattern matched" if passed else "pattern not found in final answer"
        else:
            passed, detail = False, f"unsupported grader type: {gtype}"
        results.append({"name": g["name"], "type": gtype, "passed": passed, "detail": detail})
    score = sum(1 for r in results if r["passed"]) / len(results) if results else 1.0
    return score, results


MINIMAL_POM = ("<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
               "<artifactId>eval-fixture</artifactId><version>0.0.1</version></project>")

# Seconds to keep reading after the Skill call so its tool result lands.
SKILL_GRACE = 8


def line_has_skill_call(line):
    try:
        event = json.loads(line)
    except json.JSONDecodeError:
        return '"name":"Skill"' in line.replace(" ", "")
    for node in walk(event):
        name = node.get("name") or node.get("tool_name")
        if name == "Skill" and ("arguments" in node or "input" in node
                                or node.get("type") in ("tool_use", "tool_call")):
            return True
    return False


def run_headless(tool, case, model):
    timeout = case["meta"].get("timeout_seconds", 180)
    if tool == "kimi":
        cmd = ["kimi", "-p", case["prompt"], "--output-format", "stream-json",
               "--skills-dir", str(ROOT / "skills")]
    else:
        cmd = ["codex", "exec", case["prompt"], "--json"]
    if model:
        cmd += ["-m", model] if tool == "kimi" else ["--model", model]

    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix=f"eval-{tool}-") as cwd:
        # Positive prompts say "this Spring Boot service"; an empty dir makes
        # non-Claude models refuse instead of routing, so seed a marker pom.
        Path(cwd, "pom.xml").write_text(MINIMAL_POM)
        proc = subprocess.Popen(cmd, cwd=cwd, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, bufsize=1)
        # Cut the run once routing is decided (a Skill call is observed); the
        # skills instruct full implementations that would blow the timeout.
        # kimi/codex have no max-turns flag, so the harness enforces it here.
        lines, skill_seen_at, killed = [], None, False
        deadline = started + timeout
        while True:
            wait = deadline - time.monotonic()
            if skill_seen_at is not None:
                wait = min(wait, skill_seen_at + SKILL_GRACE - time.monotonic())
            if wait <= 0:
                killed = True
                proc.kill()
                break
            ready, _, _ = select.select([proc.stdout], [], [], wait)
            if not ready:
                killed = True
                proc.kill()
                break
            line = proc.stdout.readline()
            if not line:
                proc.wait()
                break
            lines.append(line)
            if skill_seen_at is None and line_has_skill_call(line):
                skill_seen_at = time.monotonic()
        rest, stderr = proc.communicate()
        lines.append(rest)

    duration = round(time.monotonic() - started)
    out = {"durationSeconds": duration}
    stdout = "".join(lines)
    if killed and skill_seen_at is None:
        out["error"] = f"timeout after {timeout}s"
        out["trace"] = stdout[-20000:]
        return out
    if not stdout.strip():
        out["error"] = f"exit {proc.returncode}: {stderr.strip()[:300]}"
        out["trace"] = stderr[-20000:]
        return out
    skill_inputs, final_text = parse_event_stream(stdout)
    out["score"], out["graders"] = grade(case["graders"], skill_inputs, final_text)
    out["passed"] = out["score"] >= 1.0
    if not out["passed"]:
        out["trace"] = stdout[-20000:]
    return out


def run_with_claude(cases, runs, model):
    for case in cases:
        cmd = ["claude", "plugin", "eval", ".", "--case", case["name"],
               "--runs", str(runs), "--ablation", "none", "--no-publish"]
        if model:
            cmd += ["--model", model]
        proc = subprocess.run(cmd, cwd=ROOT)
        if proc.returncode != 0:
            return proc.returncode
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--tool", required=True, choices=["claude", "kimi", "codex"])
    ap.add_argument("--case", action="append", default=[], help="case name glob (repeatable)")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--model", default=None)
    args = ap.parse_args()

    binary = {"claude": "claude", "kimi": "kimi", "codex": "codex"}[args.tool]
    if not shutil.which(binary):
        sys.exit(f"`{binary}` is not on PATH. Install it first, then retry.")

    cases = load_cases(args.case)
    if not cases:
        sys.exit(f"no eval cases matched: {args.case}")

    if args.tool == "claude":
        sys.exit(run_with_claude(cases, args.runs, args.model))

    started_at = datetime.now(timezone.utc)
    out_dir = RESULTS / f"{started_at.strftime('%Y-%m-%dT%H-%M-%S')}-{args.tool}"
    out_dir.mkdir(parents=True, exist_ok=True)
    all_cases = []
    for case in cases:
        runs = []
        for i in range(args.runs):
            r = run_headless(args.tool, case, args.model)
            r["run"] = i + 1
            if "trace" in r:
                trace_path = out_dir / f"{case['name']}-run{i + 1}.jsonl"
                trace_path.write_text(r.pop("trace"))
                r["tracePath"] = str(trace_path)
            runs.append(r)
            mark = "✓" if r.get("passed") else "✗"
            note = r.get("error") or f"score {r.get('score', 0):.2f}"
            print(f"  {mark} {case['name']} run {i + 1}/{args.runs}: {note}")
        ok = [r for r in runs if r.get("passed")]
        score = sum(r.get("score", 0) for r in runs) / len(runs)
        all_cases.append({"name": case["name"], "score": score,
                          "passed": len(ok) == len(runs), "runs": runs})

    (out_dir / "aggregate-result.json").write_text(json.dumps({
        "tool": args.tool, "model": args.model, "startedAt": started_at.isoformat(),
        "cases": all_cases,
    }, indent=2))

    width = max(len(c["name"]) for c in all_cases)
    print(f"\n{'CASE':<{width}}  SCORE  RUNS  STATUS")
    for c in all_cases:
        status = "pass" if c["passed"] else "FAIL"
        print(f"{c['name']:<{width}}  {c['score']:.2f}   {len(c['runs'])}     {status}")
    print(f"\nreport: {out_dir / 'aggregate-result.json'}")
    sys.exit(0 if all(c["passed"] for c in all_cases) else 1)


if __name__ == "__main__":
    main()
