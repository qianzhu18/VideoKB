#!/usr/bin/env python3
"""End-to-end MCP client for the DoVideo knowledge adapter.

Speaks the stateless subset of MCP Streamable HTTP against POST /mcp:
initialize -> notifications/initialized -> tools/list -> tools/call.

Usage:
    python3 scripts/mcp_e2e_client.py --url http://127.0.0.1:9091/mcp \
        --token <MCP client token> [--query "语音助手能听懂哪些指令"]

Exit code 0 means every step passed; this script is the P5 acceptance test.
"""

import argparse
import json
import sys
import urllib.request


class McpClient:
    def __init__(self, url, token):
        self.url = url
        self.token = token
        self.next_id = 1

    def post(self, payload, expect_status=200):
        request = urllib.request.Request(
            self.url,
            data=json.dumps(payload).encode(),
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json, text/event-stream",
                "Authorization": f"Bearer {self.token}",
            },
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            assert response.status == expect_status, f"HTTP {response.status}"
            body = response.read().decode()
            return json.loads(body) if body.strip() else None

    def request(self, method, params=None):
        payload = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        self.next_id += 1
        if params is not None:
            payload["params"] = params
        response = self.post(payload)
        assert "error" not in response, f"{method} failed: {response['error']}"
        return response["result"]

    def notify(self, method):
        self.post({"jsonrpc": "2.0", "method": method}, expect_status=202)

    def initialize(self):
        result = self.request("initialize", {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "dovideo-e2e-client", "version": "0.1.0"},
        })
        self.notify("notifications/initialized")
        return result

    def tools_list(self):
        return self.request("tools/list")["tools"]

    def call_tool(self, name, arguments):
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        assert not result.get("isError"), f"tool {name} errored: {result}"
        return json.loads(result["content"][0]["text"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:9091/mcp")
    parser.add_argument("--token", required=True)
    parser.add_argument("--query", default="Redis 为什么快")
    parser.add_argument("--ask-query", default="MySQL 的索引为什么用 B+ 树而不是 B 树",
                        help="Question the corpus CAN answer; must yield SUPPORTED citations")
    parser.add_argument("--refusal-query", default="Kubernetes 调度器是怎么分配 Pod 的",
                        help="Out-of-corpus question; must yield INSUFFICIENT_EVIDENCE")
    parser.add_argument("--space-id", type=int, default=None,
                        help="Omit to auto-pick the user's main (non-system-default) space")
    args = parser.parse_args()

    client = McpClient(args.url, args.token)

    init = client.initialize()
    print(f"[1] initialize ok: {init['serverInfo']['name']} v{init['serverInfo']['version']}, "
          f"protocol {init['protocolVersion']}")

    tools = client.tools_list()
    print(f"[2] tools/list ok: {[t['name'] for t in tools]}")
    assert {t["name"] for t in tools} >= {
        "list_knowledge_spaces", "search_video_knowledge", "ask_video_knowledge",
        "get_video_evidence"}

    spaces = client.call_tool("list_knowledge_spaces", {})
    print(f"[3] list_knowledge_spaces ok: {[(s['id'], s['name']) for s in spaces]}")
    if not args.space_id:
        # The system-default space is the auto-created empty one; the user's main
        # library is the first non-default space (personal-product convention).
        main_space = next((s for s in spaces if not s.get("systemDefault")), None)
        args.space_id = main_space["id"] if main_space else None
        if args.space_id:
            print(f"    auto-picked main space: {args.space_id} ({main_space['name']})")
    # No --space-id means "search all spaces" — the assistant-friendly default.
    search_args = {"query": args.query, "topK": 3}
    if args.space_id:
        search_args["spaceId"] = args.space_id

    hits = client.call_tool("search_video_knowledge", search_args)
    print(f"[4] search_video_knowledge ok: {len(hits)} hit(s)")
    for hit in hits:
        print(f"    [{hit['startSec']}-{hit['endSec']}s] {hit['title']} "
              f"({hit['matchType']}, {hit['score']:.3f})")
    assert hits, "expected at least one evidence hit for the demo query"

    # get_video_evidence 只适用于视频命中；脚本命中没有 mediaId。
    video_hits = [h for h in hits if h.get("mediaId") is not None]
    assert video_hits, "expected at least one VIDEO hit with mediaId"
    evidence = client.call_tool("get_video_evidence", {
        "mediaId": video_hits[0]["mediaId"],
        "startMs": video_hits[0]["startMs"],
        "endMs": video_hits[0]["endMs"]})
    print(f"[5] get_video_evidence ok: {len(evidence)} row(s) for media {video_hits[0]['mediaId']} ({video_hits[0]['sourceType']})")
    for row in evidence:
        print(f"    [{row['startSec']}-{row['endSec']}s] {(row.get('transcript') or '')[:60]}")
    assert evidence, "expected at least one raw evidence row"

    ask_args = {"query": args.ask_query, "topK": 5}
    if args.space_id:
        ask_args["spaceId"] = args.space_id
    answer = client.call_tool("ask_video_knowledge", ask_args)
    print(f"[6] ask_video_knowledge ok: answerability={answer['answerability']}, "
          f"{len(answer.get('citations', []))} citation(s)")
    assert answer["answerability"] == "SUPPORTED", (
        f"expected SUPPORTED for in-corpus query, got: {answer}")
    assert answer["answer"].strip(), "expected non-empty answer"
    for citation in answer["citations"]:
        print(f"    [{citation['startSec']}-{citation['endSec']}s] {citation['title']}: "
              f"{citation['quote'][:50]}…")
        assert citation["quote"].strip() and citation["startMs"] >= 0, "citation must be anchored"

    refusal_args = {"query": args.refusal_query, "topK": 5}
    if args.space_id:
        refusal_args["spaceId"] = args.space_id
    refused = client.call_tool("ask_video_knowledge", refusal_args)
    print(f"[7] ask_video_knowledge refusal ok: answerability={refused['answerability']}")
    assert refused["answerability"] == "INSUFFICIENT_EVIDENCE", (
        f"expected INSUFFICIENT_EVIDENCE for out-of-corpus query, got: {refused}")
    assert not refused.get("citations"), "refusal must not carry citations"

    print("\nE2E PASSED: external assistant listed spaces, searched evidence, "
          "pulled timestamped raw evidence, asked a grounded question with verified "
          "citations, and saw the refusal guardrail — all through MCP.")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"E2E FAILED: {e}", file=sys.stderr)
        sys.exit(1)
