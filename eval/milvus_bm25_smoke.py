#!/usr/bin/env python3
"""Backfill and acceptance on the independent frozen-course snapshot; no product DB writes."""
import json
import os
import subprocess
import time
import uuid
from urllib.error import HTTPError
from pathlib import Path
from knowledge_eval import http_json, login
from prepare_snapshot_benchmark import sql, ROOT

BASE = 'http://127.0.0.1:19092'

def main():
    for container in ('video-kb-benchmark-snapshot', 'video-kb-benchmark-qdrant'):
        mounts = json.loads(subprocess.check_output(['docker', '--context', 'desktop-linux', 'inspect', container]))[0]['Mounts']
        if not any(m['Source'].startswith(('/tmp/videoagent-benchmark-', '/private/tmp/videoagent-benchmark-')) for m in mounts):
            raise SystemExit('Refusing to modify a container outside the independent snapshot')
    mapping = json.loads((ROOT/'eval/reports/evidence-block-map-20261008.json').read_text())
    session_path = Path('/tmp/videoagent-evidence-benchmark-session.json')
    session = json.loads(session_path.read_text())
    token = login(BASE, session['username'], session['password'])
    session['token'] = token; session_path.write_text(json.dumps(session)); os.chmod(session_path, 0o600)
    checks = []
    def check(name, passed):
        checks.append({'name': name, 'passed': bool(passed)})
        if not passed: raise AssertionError(name)
    def api(path, body=None, method=None, bearer=token):
        response = http_json(BASE, path, body, bearer, method)
        if response.get('code') != 0: raise RuntimeError('API failed at ' + path)
        return response['data']
    def details(**extra):
        return api('/knowledge/search/details', {'spaceId': mapping['spaceId'], 'query': '缓存',
            'topK': 8, 'strategy': 'keyword', **extra})
    def denied(path, body, bearer):
        try:
            return http_json(BASE, path, body, bearer).get('code') != 0
        except HTTPError as error:
            return error.code in (401, 403)
    before = sql('SELECT COUNT(*) FROM knowledge_source_versions; SELECT COUNT(*) FROM knowledge_embedding_cache;')
    initial = details()
    backfills = []
    for video in mapping['videos']:
        started = time.perf_counter()
        count = api(f"/knowledge/sources/{video['sourceId']}/lexical-index", {})
        backfills.append({'sourceId': video['sourceId'], 'versionId': video['versionId'],
            'documents': count, 'seconds': time.perf_counter()-started})
        print(json.dumps(backfills[-1]), flush=True)
    check('backfill indexes all 592 current retrieval blocks', sum(b['documents'] for b in backfills) == 592)
    check('backfill creates no evidence versions or embedding cache entries', before == sql(
        'SELECT COUNT(*) FROM knowledge_source_versions; SELECT COUNT(*) FROM knowledge_embedding_cache;'))
    result = details()
    check('healthy keyword search returns BM25 and no degradation warnings', bool(result['hits'])
        and not result['warnings'] and all(h['matchType'] == 'bm25' for h in result['hits']))
    check('every hit is from the published mapped generation', all(any(
        v['sourceId'] == h['sourceId'] and v['versionId'] == h['versionId'] for v in mapping['videos']) for h in result['hits']))
    check('original evidence keeps exact version and nonempty original identities', all(h['evidence']
        and all(e['versionId'] == h['versionId'] and e['segmentId'] != h['segmentId'] for e in h['evidence']) for h in result['hits']))
    hit = result['hits'][0]
    originals = api(f"/knowledge/sources/media/{hit['mediaId']}/segments?versionId={hit['versionId']}")
    by_id = {e['id']: e for e in originals}
    check('BM25 citations resolve to unchanged original text and timestamps', all(
        e['segmentId'] in by_id and by_id[e['segmentId']]['startMs'] == e['startMs']
        and by_id[e['segmentId']]['transcript'] == e['transcript'] for e in hit['evidence']))
    check('legacy search still returns an array', isinstance(api('/knowledge/search', {
        'spaceId': mapping['spaceId'], 'query': '缓存', 'strategy': 'keyword'}), list))
    check('explicit LIKE comparison retains keyword match type', all(h['matchType'] == 'keyword' for h in details(strategy='like')['hits']))
    # Independent snapshot only: deliberately lose one completion receipt, then recover it.
    video = mapping['videos'][0]
    sql(f"DELETE FROM knowledge_lexical_generations WHERE version_id={int(video['versionId'])}")
    try:
        check('incomplete backfill emits a warning instead of silently searching a partial corpus', bool(details()['warnings']))
    finally:
        api(f"/knowledge/sources/{video['sourceId']}/lexical-index", {})
    check('backfill recovery clears warning', not details()['warnings'])
    folder = api(f"/knowledge/spaces/{mapping['spaceId']}/collections", {'name': 'BM25验收-' + uuid.uuid4().hex[:6]})['id']
    target = next(v for v in mapping['videos'] if v['sourceId'] == hit['sourceId'])
    placement = api(f"/knowledge/sources/{target['sourceId']}/placements", {'spaceId': mapping['spaceId'], 'collectionId': folder})
    folder_result = details(collectionId=folder)
    check('directory-filtered BM25 never returns sibling sources', bool(folder_result['hits'])
        and all(h['sourceId'] == target['sourceId'] for h in folder_result['hits']))
    api(f"/knowledge/sources/{target['sourceId']}/placements/{placement['id']}", method='DELETE')
    check('removed placement immediately revokes folder evidence', not details(collectionId=folder)['hits'])
    username = 'bm25_stranger_' + uuid.uuid4().hex[:8]
    password = uuid.uuid4().hex + '_SnapshotOnly'
    api('/user/register', {'username': username, 'password': password}, bearer=None)
    stranger = login(BASE, username, password)
    check('other owner cannot query corpus', denied('/knowledge/search/details', {
        'spaceId': mapping['spaceId'], 'query': '缓存', 'strategy': 'keyword'}, stranger))
    check('other owner cannot backfill a source', denied(
        f"/knowledge/sources/{target['sourceId']}/lexical-index", {}, stranger))
    compose = ['docker', '--context', 'desktop-linux', 'compose', '-p', 'video-kb-milvus-experiment',
        '-f', str(ROOT/'docker-compose.milvus.yml')]
    subprocess.run(compose + ['stop', 'milvus'], check=True, capture_output=True)
    try:
        check('Milvus outage falls back with explicit warning', bool(details()['warnings']))
    finally:
        subprocess.run(compose + ['start', 'milvus'], check=True, capture_output=True)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if not details()['warnings']: break
        time.sleep(1)
    check('Milvus restart restores native BM25 with persisted data', not details()['warnings'])
    path = ROOT/'eval/reports/milvus-bm25-smoke-20261009.json'
    path.write_text(json.dumps({'date': '2026-10-09', 'milvusVersion': '2.6.24',
        'lexicalProfile': 'videokb-bm25-jieba-lowercase-v1', 'mapping': mapping,
        'initialDegradationWarnings': initial['warnings'], 'backfills': backfills, 'checks': checks,
        'realInfrastructure': True, 'realCorpus': True, 'generationModelInvoked': False,
        'productionCapacityClaim': False, 'denseMigrationComplete': False}, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'passed': len(checks), 'report': str(path)}))

if __name__ == '__main__': main()
