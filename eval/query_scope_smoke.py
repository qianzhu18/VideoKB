#!/usr/bin/env python3
"""Web/MCP scope regression on isolated real infrastructure, deterministic AI only."""
import json
import os
from pathlib import Path
import urllib.request
import uuid
from architecture_smoke import api, denied, sql

def main():
    session = json.loads(Path('/tmp/videoagent-architecture-fixture-session.json').read_text())
    fixture = json.loads((Path(__file__).parent/'reports/architecture-smoke-20261008.json').read_text())['ui_fixture']
    token = session['token']; space = fixture['spaceId']; source = fixture['sourceId']
    checks = []
    def check(name, condition):
        checks.append({'name': name, 'passed': bool(condition)})
        if not condition: raise AssertionError(name)
    def tool(name, args):
        req = urllib.request.Request('http://127.0.0.1:19091/mcp', json.dumps({
            'jsonrpc':'2.0','id':1,'method':'tools/call','params':{'name':name,'arguments':args}}).encode(),
            {'Authorization':'Bearer architecture-fixture-client','Content-Type':'application/json'}, method='POST')
        with urllib.request.urlopen(req, timeout=30) as response: data = json.load(response)['result']
        if data.get('isError'): raise AssertionError('MCP scope call failed')
        return json.loads(data['content'][0]['text'])
    query = {'query':'缓存','topK':8,'strategy':'keyword'}
    http = api('/knowledge/search/details', token, 'POST', query)
    mcp = tool('search_video_knowledge', query)
    check('omitted search scope uses same default space in Web and MCP', http['scope']['spaceId'] == space == mcp['scope']['spaceId'])
    check('same candidates with same principal and range', {h['segmentId'] for h in http['hits']} == {h['segmentId'] for h in mcp['hits']})
    check('legacy search remains array', isinstance(api('/knowledge/search', token, 'POST', query), list))
    check('folder without space rejected in HTTP', denied('/knowledge/search/details', token, 'POST', {**query,'collectionId':1}))
    empty = api('/knowledge/spaces', token, 'POST', {'name':'范围契约-'+uuid.uuid4().hex[:6]})['id']
    folder = api(f'/knowledge/spaces/{space}/collections', token, 'POST', {'name':'未就绪验收-'+uuid.uuid4().hex[:6]})['id']
    placement = api(f'/knowledge/sources/{source}/placements', token, 'POST', {'spaceId':space,'collectionId':folder})
    q = {**query,'spaceId':space,'collectionId':folder}
    try:
        # Only this disposable fixture database; never target the original product database.
        sql(f"UPDATE knowledge_sources SET status='PENDING' WHERE id={source}")
        http = api('/knowledge/search/details', token, 'POST', q); mcp = tool('search_video_knowledge', q)
        check('pending folder excludes READY sources from sibling folders', http['scope']['status'] == 'NOT_READY' and http['scope']['readySources'] == 0)
        check('MCP preserves pending state and warnings', mcp['scope']['status'] == 'NOT_READY' and bool(mcp['warnings']) and not mcp['hits'])
        ask = api('/knowledge/ask', token, 'POST', q); mcpask = tool('ask_video_knowledge', q)
        check('ask reports NOT_READY without model inference on both transports', ask['answerability'] == mcpask['answerability'] == 'NOT_READY')
        other_ready = [int(x) for x in sql(f"SELECT DISTINCT s.id FROM knowledge_sources s JOIN knowledge_placements p ON p.source_id=s.id WHERE p.space_id={space} AND s.status='READY'").splitlines()]
        try:
            if other_ready: sql("UPDATE knowledge_sources SET status='PENDING' WHERE id IN (" + ','.join(map(str, other_ready)) + ')')
            check('omitted ask scope uses same default space', tool('ask_video_knowledge',query)['scope']['spaceId'] == space)
        finally:
            if other_ready: sql("UPDATE knowledge_sources SET status='READY' WHERE id IN (" + ','.join(map(str, other_ready)) + ')')
        sql(f"UPDATE knowledge_sources SET status='FAILED' WHERE id={source}")
        check('failed ingest distinguished from empty corpus', api('/knowledge/search/details',token,'POST',q)['scope']['status'] == 'FAILED')
    finally:
        sql(f"UPDATE knowledge_sources SET status='READY' WHERE id={source}")
    api(f"/knowledge/sources/{source}/placements/{placement['id']}",token,'DELETE')
    check('removed placement no longer appears in old folder', not api('/knowledge/search/details',token,'POST',q)['hits'])
    check('explicit empty space never fans out to other spaces', tool('search_video_knowledge',{**query,'spaceId':empty})['scope']['status'] == 'EMPTY')
    strangername='scope_'+uuid.uuid4().hex[:8]
    api('/user/register',method='POST',body={'username':strangername,'password':'ScopeFixtureOnly_1'})
    stranger=api('/user/login',method='POST',body={'username':strangername,'password':'ScopeFixtureOnly_1'})['token']
    check('different owner cannot discover query state',denied('/knowledge/search/details',stranger,'POST',{**query,'spaceId':space}))
    check('different owner cannot ask inside scope',denied('/knowledge/ask',stranger,'POST',{**query,'spaceId':space}))
    path=Path(__file__).parent/'reports/query-scope-smoke-20261008.json'
    path.write_text(json.dumps({'date':'2026-10-08','fixture':'Real MySQL/Redis/Qdrant/MQ/HTTP with deterministic AI',
        'productionQualityClaim':False,'checks':checks},ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'passed':len(checks),'report':str(path)},ensure_ascii=False))

if __name__ == '__main__': main()
