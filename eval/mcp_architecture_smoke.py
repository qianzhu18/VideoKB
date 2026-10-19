#!/usr/bin/env python3
"""Real MCP -> authenticated isolated backend -> catalog/Qdrant smoke test; synthetic AI."""
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
import uuid
from architecture_smoke import api

def main():
    fixture = json.loads(Path('/tmp/videoagent-architecture-fixture-session.json').read_text())
    report = json.loads((Path(__file__).parent / f'reports/architecture-smoke-{os.environ.get("FIXTURE_REPORT_DATE", "20261008")}.json').read_text())
    scope = report['ui_fixture']
    token = fixture['token']
    second = api('/knowledge/spaces', token, 'POST', {'name': '全栈学习-' + uuid.uuid4().hex[:6]})['id']
    api(f"/knowledge/sources/{scope['sourceId']}/placements", token, 'POST', {'spaceId': second})
    checks = []
    def check(name, value):
        checks.append({'name': name, 'passed': bool(value)})
        if not value: raise AssertionError(name)
    def rpc(method, params=None, credential='architecture-fixture-client'):
        req = urllib.request.Request('http://127.0.0.1:19091/mcp', json.dumps({
            'jsonrpc': '2.0', 'id': 1, 'method': method, 'params': params or {}}).encode(), {
            'Authorization': 'Bearer ' + credential, 'Content-Type': 'application/json',
            'Accept': 'application/json, text/event-stream'}, method='POST')
        with urllib.request.urlopen(req, timeout=30) as res: return json.load(res)
    def tool(name, args):
        result = rpc('tools/call', {'name': name, 'arguments': args})['result']
        check(name + ' executes', not result.get('isError', False))
        return json.loads(result['content'][0]['text'])
    check('initialize accepted', rpc('initialize', {'protocolVersion': '2025-06-18'})['result']['serverInfo']['name'] == 'dovideo-knowledge')
    tools = rpc('tools/list')['result']['tools']
    check('five tools including folder discovery', len(tools) == 5 and any(t['name'] == 'get_knowledge_catalog' for t in tools))
    catalog = tool('get_knowledge_catalog', {'spaceId': scope['spaceId']})
    check('catalog shows folder references and independent job state', len(catalog['collections']) == 3 and catalog['ingestJobs'][0]['state'] == 'READY')
    hits = tool('search_video_knowledge', {'query': '缓存', 'topK': 5, 'strategy': 'hybrid'})['hits']
    check('default-space retrieval deduplicates shared evidence', len(hits) == 2 and len({h['segmentId'] for h in hits}) == 2)
    folder = next(f['id'] for f in catalog['collections'] if f['name'] == '前端学习')
    check('MCP folder filtering uses backend scope', len(tool('search_video_knowledge', {'query': '缓存', 'spaceId': scope['spaceId'], 'collectionId': folder, 'strategy': 'vector'})['hits']) == 2)
    evidence = tool('get_video_evidence', {'mediaId': scope['mediaId'], 'startMs': 0, 'endMs': 60000})
    check('evidence surface returns published segments', bool(evidence))
    try: rpc('tools/list', credential='unauthorized-fixture-client')
    except urllib.error.HTTPError as error: check('unknown MCP client rejected', error.code == 401)
    else: raise AssertionError('unknown MCP client accepted')
    target = Path(__file__).parent / f'reports/mcp-architecture-smoke-{os.environ.get("FIXTURE_REPORT_DATE", "20261008")}.json'
    target.write_text(json.dumps({'fixture': report['fixture'], 'production_quality_claim': False, 'checks': checks}, ensure_ascii=False, indent=2))
    print(json.dumps({'passed': len(checks), 'report': str(target)}, ensure_ascii=False))

if __name__ == '__main__': main()
