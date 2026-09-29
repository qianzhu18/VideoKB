#!/usr/bin/env python3
"""Golden-set evaluation harness for the cross-video knowledge base.

Two evaluation layers over the same golden set, so a regression can be blamed on
retrieval or generation:

- search mode: POST /knowledge/search per case x strategy x K -> media-level
  Recall@K, MRR, zero-hit refusal correctness, latency.
- ask mode: POST /knowledge/ask (hybrid, defaultK) -> answerability correctness
  (in-corpus must answer AND cite an expected video; out-of-corpus must refuse),
  citationValidRate (each quote is independently re-verified against the segment
  rows pulled from the API — the harness never trusts the server's own validation),
  keywordCoverage, latency.

Every report embeds a manifest (golden file + md5, space, model config, time) so
runs are comparable. Gates come from the golden file; exit code 2 on violation.

Usage
    python3 eval/knowledge_eval.py --base-url http://127.0.0.1:9090 \
        --username mcp_service --password '...' --golden eval/golden-v2.json \
        [--mode both] [--strategies vector keyword hybrid] [--k 1 3 5]
"""

import argparse
import hashlib
import json
import os
import re
import statistics
import string
import time
import urllib.request

# Mirrors the server's Java normalization: ASCII punctuation via string.punctuation,
# CJK punctuation enumerated (Python re has no \p{Punct}).
NORMALIZE_STRIP = re.compile(
    "[" + re.escape(string.punctuation) + r"\s，。！？、；：‘’“”【】（）《》…—·「」『』]")


def http_json(base_url, path, payload=None, token=None, method=None, timeout=120):
    url = base_url.rstrip("/") + path
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=data, method=method or ("POST" if data else "GET"))
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def login(base_url, username, password):
    result = http_json(base_url, "/user/login", {"username": username, "password": password})
    if result.get("code") != 0:
        raise SystemExit(f"login failed: {result}")
    return result["data"]["token"]


def normalize(text):
    """Same normalization as the server's verbatim check (case-folded, punct/space stripped)."""
    return NORMALIZE_STRIP.sub("", text or "").lower()


def is_refusal(case):
    if case.get("expectEmpty"):
        return True
    return not case.get("expectedMediaIds")


def hit_rank(hits, case):
    """1-based rank of the first hit on an expected media; v1 windowed cases also need overlap."""
    if is_refusal(case):
        return None
    expectations = case.get("expectAny") or ([{
        "mediaId": case["expectMediaId"],
        "startMs": case["expectStartMs"],
        "endMs": case["expectEndMs"],
    }] if case.get("expectMediaId") else
        [{"mediaId": media_id} for media_id in case.get("expectedMediaIds", [])])
    for rank, hit in enumerate(hits, start=1):
        for expected in expectations:
            same_media = hit.get("mediaId") == expected["mediaId"]
            has_window = "startMs" in expected
            overlapped = (not has_window or (hit["startMs"] < expected["endMs"]
                                             and hit["endMs"] > expected["startMs"]))
            if same_media and overlapped:
                return rank
    return None


class SegmentStore:
    """Lazily cached raw segment rows per media, used for independent quote re-verification."""

    def __init__(self, base_url, token):
        self.base_url = base_url
        self.token = token
        self.cache = {}

    def normalized_evidence(self, media_id):
        if media_id not in self.cache:
            result = http_json(self.base_url, f"/knowledge/sources/media/{media_id}/segments",
                               token=self.token)
            if result.get("code") != 0:
                self.cache[media_id] = []
            else:
                self.cache[media_id] = [normalize(" ".join(filter(None, [
                    seg.get("transcript"), seg.get("ocrText"), seg.get("summary")])))
                    for seg in result.get("data") or []]
        return self.cache[media_id]

    def quote_verified(self, citation):
        quote = normalize(citation.get("quote"))
        if len(quote) < 4:
            return False
        return any(quote in evidence for evidence in self.normalized_evidence(citation.get("mediaId")))


def evaluate_search(base_url, token, space_id, cases, strategies, ks):
    layers = {}
    for strategy in strategies:
        max_k = max(ks)
        rows = []
        for case in cases:
            started = time.perf_counter()
            result = http_json(base_url, "/knowledge/search",
                               {"spaceId": space_id, "query": case["question"],
                                "topK": max_k, "strategy": strategy}, token=token)
            if result.get("code") != 0:
                raise SystemExit(f"search failed for {case['question']!r}: {result}")
            rows.append({"case": case, "hits": result.get("data") or [],
                         "latencyMs": (time.perf_counter() - started) * 1000})

        answerable = [row for row in rows if not is_refusal(row["case"])]
        unanswerable = [row for row in rows if is_refusal(row["case"])]

        recall_at_k = {}
        for k in ks:
            recalled = sum(1 for row in answerable
                           if (rank := hit_rank(row["hits"], row["case"])) and rank <= k)
            recall_at_k[k] = round(recalled / len(answerable), 4) if answerable else 0.0

        reciprocal = [1.0 / rank for row in answerable
                      if (rank := hit_rank(row["hits"], row["case"])) is not None]
        mrr = round(sum(reciprocal) / len(answerable), 4) if answerable else 0.0
        correct_refusals = sum(1 for row in unanswerable if not row["hits"])
        latencies = sorted(row["latencyMs"] for row in rows)

        layers[strategy] = {
            "recallAtK": recall_at_k, "mrr": mrr,
            "refusalCorrectRate": round(correct_refusals / len(unanswerable), 4) if unanswerable else None,
            "refusalCorrect": f"{correct_refusals}/{len(unanswerable)}",
            "p50LatencyMs": round(statistics.median(latencies), 1),
            "p95LatencyMs": round(latencies[max(0, int(len(latencies) * 0.95) - 1)], 1),
            "answerableMisses": [row["case"]["id"] for row in answerable
                                 if hit_rank(row["hits"], row["case"]) is None],
            "falsePositives": [row["case"]["id"] for row in unanswerable if row["hits"]],
        }
        print(f"[search:{strategy}] Recall@K={recall_at_k} MRR={mrr} "
              f"refusal={layers[strategy]['refusalCorrect']} "
              f"p95={layers[strategy]['p95LatencyMs']}ms "
              f"misses={layers[strategy]['answerableMisses']}")
    return layers


def evaluate_ask(base_url, token, space_id, cases, store):
    rows = []
    for case in cases:
        started = time.perf_counter()
        result = http_json(base_url, "/knowledge/ask",
                           {"spaceId": space_id, "query": case["question"],
                            "topK": case.get("k", 5), "strategy": "hybrid"},
                           token=token, timeout=300)
        if result.get("code") != 0:
            raise SystemExit(f"ask failed for {case['question']!r}: {result}")
        answer = result.get("data") or {}
        rows.append({"case": case, "answer": answer,
                     "latencyMs": (time.perf_counter() - started) * 1000})
        print(f"[ask] {case['id']}: {answer.get('answerability')} "
              f"cites={len(answer.get('citations') or [])} "
              f"{row_summary(case, answer)}", flush=True)
    # per-case rows are kept in the report so misses can be attributed later
    # (warnings distinguish "model refused" from "citation failed verification").
    detail = [{"id": row["case"]["id"],
               "question": row["case"]["question"],
               "expectedMediaIds": row["case"].get("expectedMediaIds", []),
               "answerability": row["answer"].get("answerability"),
               "warnings": row["answer"].get("warnings") or [],
               "citationMediaIds": sorted({c.get("mediaId")
                                           for c in row["answer"].get("citations") or []}),
               "latencyMs": round(row["latencyMs"])} for row in rows]

    answerable = [row for row in rows if not is_refusal(row["case"])]
    unanswerable = [row for row in rows if is_refusal(row["case"])]

    # answerability correctness: in-corpus -> SUPPORTED with a citation on an expected
    # media; out-of-corpus -> INSUFFICIENT_EVIDENCE.
    def answered_correctly(row):
        answer = row["answer"]
        if answer.get("answerability") != "SUPPORTED":
            return False
        expected = set(row["case"].get("expectedMediaIds", []))
        return any(c.get("mediaId") in expected for c in answer.get("citations") or [])

    correct_answers = sum(1 for row in answerable if answered_correctly(row))
    correct_refusals = sum(1 for row in unanswerable
                           if row["answer"].get("answerability") == "INSUFFICIENT_EVIDENCE")

    # independent citation re-verification + keyword coverage
    all_citations = [c for row in answerable for c in row["answer"].get("citations") or []]
    verified = [store.quote_verified(c) for c in all_citations]
    coverage_values = []
    for row in answerable:
        keywords = row["case"].get("expectedKeywords") or []
        if not keywords:
            continue
        text = row["answer"].get("answer") or ""
        coverage_values.append(sum(1 for kw in keywords if kw in text) / len(keywords))
    latencies = sorted(row["latencyMs"] for row in rows)

    layer = {
        "answerableCorrectRate": round(correct_answers / len(answerable), 4) if answerable else 0.0,
        "answerableCorrect": f"{correct_answers}/{len(answerable)}",
        "refusalCorrectRate": round(correct_refusals / len(unanswerable), 4) if unanswerable else None,
        "refusalCorrect": f"{correct_refusals}/{len(unanswerable)}",
        "citationValidRate": round(sum(1 for v in verified if v) / len(verified), 4) if verified else None,
        "citationValid": f"{sum(1 for v in verified if v)}/{len(verified)}",
        "keywordCoverage": round(sum(coverage_values) / len(coverage_values), 4) if coverage_values else None,
        "p50LatencyMs": round(statistics.median(latencies), 1),
        "p95LatencyMs": round(latencies[max(0, int(len(latencies) * 0.95) - 1)], 1),
        "answerableMisses": [row["case"]["id"] for row in answerable if not answered_correctly(row)],
        "falsePositives": [row["case"]["id"] for row in unanswerable
                           if row["answer"].get("answerability") != "INSUFFICIENT_EVIDENCE"],
        "caseDetails": detail,
    }
    print(f"[ask] answerable={layer['answerableCorrect']} refusal={layer['refusalCorrect']} "
          f"citationValid={layer['citationValid']} keywordCoverage={layer['keywordCoverage']} "
          f"p95={layer['p95LatencyMs']}ms")
    return layer


def row_summary(case, answer):
    if answer.get("answerability") == "SUPPORTED":
        medias = sorted({c.get("mediaId") for c in answer.get("citations") or []})
        return f"mediaIds={medias} expected={case.get('expectedMediaIds')}"
    return "refused"


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://127.0.0.1:9090")
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--golden", default="eval/golden-v2.json")
    parser.add_argument("--mode", default="both", choices=["search", "ask", "both"])
    parser.add_argument("--strategies", nargs="+", default=["vector", "keyword", "hybrid"],
                        choices=["vector", "keyword", "hybrid"])
    parser.add_argument("--k", nargs="+", type=int, default=[1, 3, 5])
    parser.add_argument("--out-dir", default="eval/reports")
    parser.add_argument("--label", default="", help="report label, e.g. 'baseline' or 'tuned-min-score-0.35'")
    parser.add_argument("--only", nargs="*", default=None,
                        help="Restrict to these case ids (miss re-runs / attribution)")
    args = parser.parse_args()

    with open(args.golden, encoding="utf-8") as handle:
        golden_text = handle.read()
    golden = json.loads(golden_text)
    space_id = golden["spaceId"]
    cases = golden["cases"]
    if args.only:
        cases = [case for case in cases if case["id"] in set(args.only)]
    token = login(args.base_url, args.username, args.password)

    report = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "label": args.label,
        "manifest": {
            "golden": args.golden,
            "goldenMd5": hashlib.md5(golden_text.encode()).hexdigest(),
            "spaceId": space_id,
            "caseCount": len(cases),
            "llmModel": os.environ.get("LLM_MODEL", "Qwen/Qwen3.8-27B"),
            "embeddingModel": os.environ.get("EMBEDDING_MODEL", "BAAI/bge-m3"),
            "mode": args.mode,
            "strategies": args.strategies,
            "ks": args.k,
        },
        "gates": golden.get("gates"),
    }

    if args.mode in ("search", "both"):
        report["search"] = evaluate_search(args.base_url, token, space_id, cases,
                                           args.strategies, args.k)
    if args.mode in ("ask", "both"):
        report["ask"] = evaluate_ask(args.base_url, token, space_id, cases,
                                     SegmentStore(args.base_url, token))

    failures = None if args.only else check_gates(report, golden.get("gates"))
    report["gateResults"] = "SKIPPED (subset run)" if args.only else (failures or "PASS")
    os.makedirs(args.out_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    suffix = f"-{args.label}" if args.label else ""
    output = f"{args.out_dir}/eval-{stamp}{suffix}.json"
    with open(output, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
    print(f"\nreport written to {output}")
    if failures:
        print("GATE FAILURES:")
        for failure in failures:
            print(f"  - {failure}")
        raise SystemExit(2)
    print("GATES PASSED")


def check_gates(report, gates):
    """Gates judge the default production path: hybrid retrieval @ K=5 and the ask layer."""
    if not gates:
        return None
    failures = []
    hybrid = (report.get("search") or {}).get("hybrid") or {}
    recall5 = (hybrid.get("recallAtK") or {}).get(5)
    if recall5 is not None and recall5 < gates["mediaRecallAt5"]:
        failures.append(f"hybrid mediaRecall@5 {recall5} < {gates['mediaRecallAt5']}")
    ask = report.get("ask")
    if ask:
        if ask.get("refusalCorrectRate") is not None and ask["refusalCorrectRate"] < gates["refusalCorrectRate"]:
            failures.append(f"ask refusalCorrectRate {ask['refusalCorrectRate']} < {gates['refusalCorrectRate']}")
        if ask.get("citationValidRate") is not None and ask["citationValidRate"] < gates["citationValidRate"]:
            failures.append(f"ask citationValidRate {ask['citationValidRate']} < {gates['citationValidRate']}")
        if ask.get("keywordCoverage") is not None and ask["keywordCoverage"] < gates["keywordCoverage"]:
            failures.append(f"ask keywordCoverage {ask['keywordCoverage']} < {gates['keywordCoverage']}")
    return failures


if __name__ == "__main__":
    main()
