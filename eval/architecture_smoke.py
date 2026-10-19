#!/usr/bin/env python3
"""Architecture acceptance against the isolated fixture runtime, not retrieval-quality evaluation.

AI is deterministic: this measures DB/MQ/vector/catalog behavior, not ASR or model accuracy.
Run scripts/architecture-fixture.sh first. Saves only assertions and timings, never tokens.
"""
import concurrent.futures
import json
import os
from pathlib import Path
import statistics
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "http://127.0.0.1:19090"
CHECKS = []

def check(name, condition):
    CHECKS.append({"name": name, "passed": bool(condition)})
    if not condition:
        raise AssertionError(name)

def raw(path, token=None, method="GET", body=None, content_type="application/json"):
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = "Bearer " + token
    if isinstance(body, (dict, list)):
        body = json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, body, headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return res.status, res.read()
    except urllib.error.HTTPError as err:
        return err.code, err.read()

def api(path, token=None, method="GET", body=None):
    status, response = raw(path, token, method, body)
    data = json.loads(response)
    if status >= 400 or data.get("code") != 0:
        raise RuntimeError(f"{method} {path}: {status} {data.get('message')}")
    return data["data"]

def denied(path, token, method="GET", body=None):
    status, response = raw(path, token, method, body)
    return status >= 400 or json.loads(response).get("code", 0) != 0

def sql(statement):
    result = subprocess.run(["docker", "exec", "-i", os.environ.get("FIXTURE_MYSQL_CONTAINER", "video-kb-fixture-mysql-1"), "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B videoagent_arch_20261007'],
        input=statement, text=True, capture_output=True, check=True)
    return result.stdout.strip()

def wait_job(token, media_id, state, timeout=70):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        jobs = api("/knowledge/ingest-jobs", token)
        job = next((j for j in jobs if j["mediaId"] == media_id), None)
        if job and job["state"] == state:
            return job
        time.sleep(0.5)
    raise AssertionError(f"job did not reach {state}")

def main():
    run_id = uuid.uuid4().hex[:8]
    username = "arch_" + run_id
    password = "ArchitectureFixtureOnly_1"
    user = api("/user/register", method="POST", body={"username": username, "password": password, "nickname": "社区架构验收"})
    token = api("/user/login", method="POST", body={"username": username, "password": password})["token"]
    api("/user/register", method="POST", body={"username": "other_" + run_id, "password": password})
    stranger = api("/user/login", method="POST", body={"username": "other_" + run_id, "password": password})["token"]
    credentials = Path("/tmp/videoagent-architecture-fixture-session.json")
    credentials.write_text(json.dumps({"username": username, "password": password, "token": token}))
    os.chmod(credentials, 0o600)
    space = api("/knowledge/spaces", token)[0]["id"]
    backend = api(f"/knowledge/spaces/{space}/collections", token, "POST", {"name": "后端学习"})["id"]
    frontend = api(f"/knowledge/spaces/{space}/collections", token, "POST", {"name": "前端学习"})["id"]
    child = api(f"/knowledge/spaces/{space}/collections", token, "POST", {"name": "缓存", "parentId": backend})["id"]
    boundary = "architecture" + run_id
    payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="跨课程缓存复习.mp4"\r\n'
               f'Content-Type: video/mp4\r\n\r\nfixture-video-{run_id}\r\n--{boundary}--\r\n').encode()
    status, response = raw("/media/upload", token, "POST", payload, "multipart/form-data; boundary=" + boundary)
    uploaded = json.loads(response)
    check("upload accepted", status == 200 and uploaded["code"] == 0)
    media_id = uploaded["data"]["id"]
    check("job exists immediately after upload", any(j["mediaId"] == media_id for j in api("/knowledge/ingest-jobs", token)))
    wait_job(token, media_id, "READY")
    source = next(s for s in api(f"/knowledge/sources?spaceId={space}", token) if s["mediaId"] == media_id)
    source_id = source["id"]
    check("knowledge READY without report task", source["status"] == "READY" and not api("/analysis/tasks", token))
    moved = api(f"/knowledge/sources/{source_id}/location", token, "PATCH", {"spaceId": space, "collectionId": child})
    reference = api(f"/knowledge/sources/{source_id}/placements", token, "POST", {"spaceId": space, "collectionId": frontend})
    again = api(f"/knowledge/sources/{source_id}/placements", token, "POST", {"spaceId": space, "collectionId": frontend})
    check("reference creation is idempotent", reference["id"] == again["id"])
    check("both folders show the same content identity", all(
        any(s["id"] == source_id for s in api(f"/knowledge/sources?spaceId={space}&collectionId={folder}", token))
        for folder in (child, frontend)))
    def search(strategy, folder=None):
        return api("/knowledge/search", token, "POST", {"spaceId": space, "collectionId": folder,
            "query": "缓存", "strategy": strategy, "topK": 5})
    for folder in (backend, frontend):
        for strategy in ("keyword", "vector", "hybrid"):
            check(f"{strategy} resolves reference/subtree {folder}", any(h["sourceId"] == source_id for h in search(strategy, folder)))
    hits = search("hybrid")
    check("space search deduplicates placements", len({h["segmentId"] for h in hits}) == len(hits) == 2)
    check("foreign tenant cannot reference", denied(f"/knowledge/sources/{source_id}/placements", stranger, "POST", {"spaceId": space}))
    check("foreign tenant cannot search", denied("/knowledge/search", stranger, "POST", {"spaceId": space, "query": "缓存"}))
    old_version = sql(f"SELECT current_version FROM knowledge_sources WHERE id={source_id};")
    old_segments = sql(f"SELECT COUNT(*) FROM knowledge_segments s JOIN knowledge_source_versions v ON s.version_id=v.id WHERE s.source_id={source_id} AND v.version_no={old_version};")
    raw("/architecture-fixture/embedding-failure?enabled=true", method="POST")
    check("failed rebuild surfaces an error", denied(f"/knowledge/sources/{source_id}/reindex", token, "POST"))
    check("failed rebuild preserves READY and published pointer", sql(f"SELECT CONCAT(status,':',current_version) FROM knowledge_sources WHERE id={source_id};") == "READY:" + old_version)
    check("failed rebuild preserves original evidence", sql(f"SELECT COUNT(*) FROM knowledge_segments s JOIN knowledge_source_versions v ON s.version_id=v.id WHERE s.source_id={source_id} AND v.version_no={old_version};") == old_segments)
    check("lexical retrieval works while embeddings fail", len(search("keyword", frontend)) == 2)
    check("hybrid falls back while embeddings fail", len(search("hybrid", frontend)) == 2)
    raw("/architecture-fixture/embedding-failure?enabled=false", method="POST")
    api(f"/knowledge/sources/{source_id}/reindex", token, "POST")
    new_hits = search("vector", frontend)
    check("successful rebuild publishes a new generation", sql(f"SELECT current_version FROM knowledge_sources WHERE id={source_id};") != old_version)
    check("old generations do not consume search topK", len(new_hits) == 2 and not ({h["segmentId"] for h in hits} & {h["segmentId"] for h in new_hits}))
    before_retry = sql(f"SELECT current_version FROM knowledge_sources WHERE id={source_id};")
    check("explicit rebuild job accepted for READY content", api(f"/knowledge/ingest-jobs/sources/{source_id}/retry", token, "POST"))
    wait_job(token, media_id, "READY")
    check("explicit job rebuild publishes another generation", sql(f"SELECT current_version FROM knowledge_sources WHERE id={source_id};") != before_retry)
    check("folder containing a reference cannot be deleted", denied(f"/knowledge/collections/{frontend}", token, "DELETE"))
    api(f"/knowledge/sources/{source_id}/placements/{reference['id']}", token, "DELETE")
    check("unlink removes only current folder results", not search("hybrid", frontend) and bool(search("hybrid", backend)))
    check("last reference cannot be unlinked", denied(f"/knowledge/sources/{source_id}/placements/{moved['placementId']}", token, "DELETE"))
    # Restore both associations for the separate Vue acceptance run.
    api(f"/knowledge/sources/{source_id}/placements", token, "POST", {"spaceId": space, "collectionId": frontend})
    api(f"/analysis/ai?id={media_id}&goal=fixture-report-failure&mode=GENERAL", token, "POST")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        report_tasks = api("/analysis/tasks", token)
        if any(t["mediaId"] == media_id and t["state"] == "FAILED" for t in report_tasks): break
        time.sleep(.5)
    else: raise AssertionError("fixture report did not fail as expected")
    check("report failure cannot change knowledge readiness", sql(f"SELECT status FROM knowledge_sources WHERE id={source_id};") == "READY" and bool(search("hybrid", backend)))
    catalog = api(f"/knowledge/spaces/{space}/catalog", token)
    check("catalog discovers both folder memberships and job", len([p for p in catalog["placements"] if p["sourceId"] == source_id]) == 2 and len(catalog["ingestJobs"]) == 1)
    check("catalog rejects other tenant", denied(f"/knowledge/spaces/{space}/catalog", stranger))
    records = []
    def one(index):
        strategy = ("vector", "keyword", "hybrid")[index % 3]
        if index == 10: api(f"/knowledge/sources/{source_id}/reindex", token, "POST")
        if index % 5 == 0: api("/knowledge/ingest-jobs", token)
        started = time.perf_counter()
        result = search(strategy, backend if index % 2 else frontend)
        return {"strategy": strategy, "seconds": time.perf_counter() - started, "hits": len(result)}
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as workers:
        records = list(workers.map(one, range(90)))
    check("mixed concurrent retrieval has no errors/duplicates", all(r["hits"] == 2 for r in records))
    check("ordinary users cannot read system metrics", raw("/actuator/prometheus", token)[0] == 403)
    sql(f"UPDATE users SET role='ADMIN' WHERE id={user['userInfo']['id']};")
    status, metrics = raw("/actuator/prometheus", token)
    check("stage metrics exported", status == 200 and b"knowledge_stage_seconds" in metrics)
    check("metrics require bearer authentication", raw("/actuator/prometheus")[0] == 401)
    timings = {}
    for strategy in ("vector", "keyword", "hybrid"):
        values = sorted(r["seconds"] for r in records if r["strategy"] == strategy)
        timings[strategy] = {"requests": len(values), "p50_seconds": statistics.median(values), "p95_seconds": values[int(len(values) * .95) - 1]}
    report = {"fixture": "2 synthetic timestamped segments, deterministic three-dimensional embeddings; real MySQL/Redis/RocketMQ/Qdrant/MinIO",
              "production_quality_claim": False, "checks": CHECKS, "concurrency": 8, "mixed_workload": "90 retrieval requests + 18 job-status reads + 1 index rebuild, concurrent", "latency": timings,
              "ui_fixture": {"spaceId": space, "sourceId": source_id, "mediaId": media_id}}
    target = Path(__file__).parent / "reports" / f"architecture-smoke-{os.environ.get('FIXTURE_REPORT_DATE', '20261008')}.json"
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({"passed": len(CHECKS), "report": str(target), "latency": timings}, ensure_ascii=False))

if __name__ == "__main__":
    main()
