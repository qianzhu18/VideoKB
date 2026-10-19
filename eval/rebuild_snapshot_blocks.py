#!/usr/bin/env python3
"""Reindex only the independent benchmark copy with exact frozen raw windows."""
import json
import os
import time
from pathlib import Path
from knowledge_eval import http_json
from prepare_snapshot_benchmark import sql
from evidence_metrics import validate_golden

ROOT = Path(__file__).resolve().parent.parent

def main():
    golden = json.loads((ROOT/'eval/evidence-golden-v1.json').read_text())
    corpus = validate_golden(golden,ROOT)
    mapping = json.loads((ROOT/'eval/reports/evidence-baseline-map-20261008.json').read_text())
    # The named container must remain an independent /tmp snapshot.
    import subprocess
    for container in ('video-kb-benchmark-snapshot','video-kb-benchmark-qdrant'):
        mounts=json.loads(subprocess.check_output(['docker','--context','desktop-linux','inspect',container]))[0]['Mounts']
        if not any('/tmp/videoagent-benchmark-' in m['Source'] for m in mounts):
            raise SystemExit('Refusing to reindex outside independent snapshot')
    session_path=Path('/tmp/videoagent-evidence-benchmark-session.json')
    session=json.loads(session_path.read_text());base='http://127.0.0.1:19092'
    session['token']=http_json(base,'/user/login',{'username':session['username'],'password':session['password']})['data']['token']
    session_path.write_text(json.dumps(session));os.chmod(session_path,0o600)
    observations=[]
    for item in mapping['videos']:
        video=next(v for v in corpus['videos'] if v['mediaKey']==item['mediaKey'])
        started=time.perf_counter()
        result=http_json(base,'/knowledge/ingest/import-transcript',{'mediaId':item['mediaId'],'segments':video['segments']},session['token'])
        if result.get('code')!=0:raise RuntimeError('Reindex failed for '+item['mediaKey']+': '+str(result.get('message')))
        current=json.loads(sql(f"SELECT JSON_OBJECT('versionId',v.id,'versionNo',v.version_no,'indexProfile',v.index_profile,'vectorDimension',v.vector_dimension,'parserVersion',v.parser_version,'embeddingModel',v.embedding_model) FROM knowledge_source_versions v JOIN knowledge_sources s ON s.id=v.source_id AND s.current_version=v.version_no WHERE s.id={item['sourceId']} AND v.status='READY'"))
        original_count=int(sql(f"SELECT COUNT(*) FROM knowledge_segments WHERE version_id={item['versionId']}"))
        assert original_count==len(video['segments']), 'Old original evidence was deleted'
        observations.append({'mediaKey':item['mediaKey'],'rawWindows':len(video['segments']),'seconds':time.perf_counter()-started,
            'retrievalBlocks':int(sql(f"SELECT COUNT(*) FROM knowledge_retrieval_blocks WHERE version_id={current['versionId']}")),
            'priorVersionId':item['versionId'],'published':current})
        item.update(current)
        print(json.dumps({'reindexed':item['mediaKey'],'blocks':observations[-1]['retrievalBlocks']},ensure_ascii=False),flush=True)
    mapping['vectorOrigin']='Real BGE-M3 embeddings of frozen raw-boundary blocks; prior generation retained'
    mapping['summaryOrigin']='No generated summaries in the new retrieval blocks'
    mapping['rebuildObservations']=observations
    path=ROOT/'eval/reports/evidence-block-map-20261008.json'
    path.write_text(json.dumps(mapping,ensure_ascii=False,indent=2)+'\n')

if __name__=='__main__':main()
