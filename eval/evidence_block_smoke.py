#!/usr/bin/env python3
"""Publication, raw-time mapping, reuse and failure tests on isolated fixture services."""
import json
import urllib.request
import uuid
from pathlib import Path
from architecture_smoke import api, raw, sql, denied

def main():
    urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))
    session=json.loads(Path('/tmp/videoagent-architecture-fixture-session.json').read_text())
    fixture=json.loads((Path(__file__).parent/'reports/architecture-smoke-20261008.json').read_text())['ui_fixture']
    token=session['token']; source=fixture['sourceId']; media=fixture['mediaId']; space=fixture['spaceId']
    checks=[]
    def check(name,condition):
        checks.append({'name':name,'passed':bool(condition)})
        if not condition:raise AssertionError(name)
    def count_cache():return int(sql('SELECT COUNT(*) FROM knowledge_embedding_cache'))
    old=api(f'/knowledge/sources/media/{media}/segments',token)
    oldversion=old[0]['versionId']
    api(f'/knowledge/sources/{source}/reindex',token,'POST')
    new=api(f'/knowledge/sources/media/{media}/segments',token)
    newversion=new[0]['versionId']
    check('new raw generation preserves exact original text and times',[(r['startMs'],r['endMs'],r['transcript'],r['ocrText']) for r in old]==[(r['startMs'],r['endMs'],r['transcript'],r['ocrText']) for r in new])
    check('old published original evidence remains readable',api(f'/knowledge/sources/media/{media}/segments?versionId={oldversion}',token)==old)
    query={'spaceId':space,'query':'缓存','topK':20,'strategy':'keyword'}
    hits=[h for h in api('/knowledge/search',token,'POST',query) if h['sourceId']==source]
    check('search returns block profile and original evidence mapping',bool(hits) and all(h['indexProfile']=='raw-boundary-v1-max1400-overlap1' and h['evidence'] for h in hits))
    check('each evidence id maps to the exact raw version/time',all(any(r['id']==e['segmentId'] and r['versionId']==e['versionId']==newversion and (r['startMs'],r['endMs'])==(e['startMs'],e['endMs']) for r in new) for h in hits for e in h['evidence']))
    before=count_cache()
    raw('/architecture-fixture/embedding-failure?enabled=true',token,'POST')
    try:
        api(f'/knowledge/sources/{source}/reindex',token,'POST')
        check('identical rebuild works with embedding provider unavailable using cache',count_cache()==before)
        current=api(f'/knowledge/sources/media/{media}/segments',token)
        status,body=raw('/knowledge/ingest/import-transcript',token,'POST',{'mediaId':media,'segments':[{'startMs':0,'endMs':60000,'text':'尚未缓存的新证据-'+uuid.uuid4().hex}]})
        check('new uncached input fails visibly while embedding provider unavailable',status>=400 or json.loads(body).get('code')!=0)
        check('failed generation keeps old published raw evidence',api(f'/knowledge/sources/media/{media}/segments',token)==current)
        check('failed generation not recalled',all(h['versionId']==current[0]['versionId'] for h in api('/knowledge/search',token,'POST',query) if h['sourceId']==source))
    finally:
        raw('/architecture-fixture/embedding-failure?enabled=false',token,'POST')
        api('/knowledge/ingest/import-transcript',token,'POST',{'mediaId':media,'segments':[{'startMs':r['startMs'],'endMs':r['endMs'],'text':r['transcript'],'ocr':[r['ocrText']] if r['ocrText'] else []} for r in old]})
    check('unpublished version cannot be read',denied(f'/knowledge/sources/media/{media}/segments?versionId=99999999',token))
    def tool(name,args):
        req=urllib.request.Request('http://127.0.0.1:19091/mcp',json.dumps({'jsonrpc':'2.0','id':1,'method':'tools/call','params':{'name':name,'arguments':args}}).encode(),{'Authorization':'Bearer architecture-fixture-client','Content-Type':'application/json'},method='POST')
        with urllib.request.urlopen(req,timeout=30) as response:result=json.load(response)['result']
        if result.get('isError'):raise AssertionError('MCP raw evidence failed')
        return json.loads(result['content'][0]['text'])
    mcprows=tool('get_video_evidence',{'mediaId':media,'versionId':oldversion,'startMs':0,'endMs':120000})
    check('MCP exposes original segment id and requested historical version',bool(mcprows) and all(r['versionId']==oldversion and r['segmentId'] for r in mcprows))
    path=Path(__file__).parent/'reports/evidence-block-smoke-20261008.json'
    path.write_text(json.dumps({'date':'2026-10-08','fixture':'Real MySQL/Redis/Qdrant/MQ/HTTP; deterministic AI',
        'productionQualityClaim':False,'checks':checks},ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'passed':len(checks),'report':str(path)},ensure_ascii=False))

if __name__=='__main__':main()
