#!/usr/bin/env python3
"""Assign an independently copied 13-course corpus to an ephemeral benchmark principal.

Only writes named /tmp snapshot containers, never the product DB/vector directory.
Original DB/vector files must be copied while the original services are stopped.
Keeps prior real summaries/vectors; query embedding uses the actual configured provider.
"""
import hashlib
import json
import os
import shlex
import subprocess
import urllib.request
import uuid
from pathlib import Path
from knowledge_eval import http_json
from evidence_metrics import normalize

ROOT = Path(__file__).resolve().parent.parent
DB = 'video-kb-benchmark-snapshot'
VECTORS = 'video-kb-benchmark-qdrant'

def sql(statement):
    return subprocess.check_output(['docker', '--context', 'desktop-linux', 'exec', '-i', DB, 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot -N -r media_db'],
        input=statement.encode()).decode().strip()

def main():
    for container in (DB, VECTORS):
        mounts = json.loads(subprocess.check_output(['docker','--context','desktop-linux','inspect',container]))[0]['Mounts']
        if not any(m['Source'].startswith('/tmp/videoagent-benchmark-') or m['Source'].startswith('/private/tmp/videoagent-benchmark-') for m in mounts):
            raise SystemExit('Refusing to change a container without the independent snapshot mount')
    base = 'http://127.0.0.1:19092'
    username = 'evidence_' + uuid.uuid4().hex[:10]
    password = uuid.uuid4().hex + '_Benchmark'
    user = http_json(base,'/user/register',{'username':username,'password':password})['data']['userInfo']['id']
    token = http_json(base,'/user/login',{'username':username,'password':password})['data']['token']
    rows = [json.loads(line) for line in sql("""
      SELECT JSON_OBJECT('sourceId',s.id,'mediaId',s.media_id,'title',s.title,
        'versionId',v.id,'versionNo',v.version_no,'embeddingModel',v.embedding_model,'parserVersion',v.parser_version)
      FROM knowledge_sources s JOIN knowledge_source_versions v ON s.id=v.source_id AND s.current_version=v.version_no
      WHERE s.space_id=14 AND s.status='READY' ORDER BY s.id;
      """).splitlines()]
    corpus_path = ROOT/'evaluation/seeds/course-index-snapshot-v1.json'
    corpus = json.loads(corpus_path.read_text())
    if len(rows)!=13: raise SystemExit('Expected the exact 13-course snapshot corpus')
    evidence = {}
    for row in rows:
        seq = int(row['title'].split('-')[0])
        row['mediaKey'] = f'bili-mianshi-{seq:02}'
        original = corpus['videos'][seq-1]
        actual = [json.loads(line) for line in sql(f"""
          SELECT JSON_OBJECT('id',id,'startMs',start_ms,'endMs',end_ms,'transcript',transcript)
          FROM knowledge_segments WHERE version_id={row['versionId']} ORDER BY start_ms;
          """).splitlines()]
        if len(actual)!=len(original['segments']) or any(
                (a['startMs'],a['endMs'],normalize(a['transcript'])) != (s['startMs'],s['endMs'],normalize(s['text']))
                for a,s in zip(actual,original['segments'])):
            raise SystemExit('Snapshot evidence differs from the frozen original corpus')
        evidence[row['mediaKey']] = {'windows':len(actual)}
    sql(f'UPDATE knowledge_spaces SET owner_user_id={user} WHERE id=14;'
        f'UPDATE knowledge_sources SET owner_user_id={user} WHERE space_id=14;'
        f'UPDATE media_files SET user_id={user} WHERE id IN ('+','.join(str(r['mediaId']) for r in rows)+');')
    env = {}
    for line in (ROOT/'.env').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key,value = line.split('=',1)
            parsed = shlex.split(value,comments=True)
            env[key] = parsed[0] if parsed else ''
    collection = env.get('QDRANT_COLLECTION','video_chunks')
    req = urllib.request.Request(f'http://127.0.0.1:16333/collections/{collection}/points/payload?wait=true',
        json.dumps({'payload':{'userId':user},'filter':{'must':[{'key':'sourceId','match':{'any':[r['sourceId'] for r in rows]}}]}}).encode(),
        {'api-key':env['QDRANT_API_KEY'],'Content-Type':'application/json'}, method='POST')
    with urllib.request.urlopen(req) as response:
        assert json.load(response)['status']=='ok'
    session = Path('/tmp/videoagent-evidence-benchmark-session.json')
    session.write_text(json.dumps({'token':token,'username':username,'password':password}))
    os.chmod(session,0o600)
    mapping = {'corpusSha256':hashlib.sha256(corpus_path.read_bytes()).hexdigest(),'spaceId':14,
        'videos':rows,'realEmbedding':True,'embeddingModel':'BAAI/bge-m3',
        'vectorOrigin':'Copied prior real index; unchanged vector values, owner payload assigned to fixture principal',
        'summaryOrigin':'Copied prior real production-path five-minute summaries',
        'nativeAsrRerun':False,'windows':evidence,'billingCost':None}
    path=ROOT/'eval/reports/evidence-baseline-map-20261008.json'
    path.write_text(json.dumps(mapping,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'prepared':True,'videos':len(rows),'windows':sum(v['windows'] for v in evidence.values()),'map':str(path)},ensure_ascii=False))

if __name__=='__main__':main()
