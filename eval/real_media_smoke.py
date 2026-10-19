#!/usr/bin/env python3
"""Real local course + archived transcript import/playback acceptance. AI is the fixture's substitute.

Run after architecture_smoke.py. --media must be a real playable, local media file;
--media-key identifies its archived transcript in the committed seed manifest.
This does not measure native ASR or real embedding/answer quality.
"""
import argparse
import hashlib
import json
import subprocess
import urllib.request
import uuid
from pathlib import Path
from architecture_smoke import api, raw, wait_job

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--media', required=True, type=Path)
    parser.add_argument('--media-key', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    seed = root / 'evaluation/seeds/bilibili-mianshi-v1.json'
    video = next(v for v in json.loads(seed.read_text())['videos'] if v['mediaKey'] == args.media_key)
    probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries',
        'format=duration,size:stream=codec_name', '-of', 'json', str(args.media)]))
    duration = float(probe['format']['duration'])
    assert abs(duration * 1000 - video['durationMs']) < 60000, 'media/transcript duration mismatch'
    # Decode at a non-zero timestamp; reading only a header is insufficient.
    subprocess.run(['ffmpeg', '-v', 'error', '-ss', '75', '-i', str(args.media), '-t', '3',
        '-f', 'null', '-'], check=True, capture_output=True)
    token = json.loads(Path('/tmp/videoagent-architecture-fixture-session.json').read_text())['token']
    boundary = 'course-' + uuid.uuid4().hex
    payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="real-course.mp4"\r\n'
        'Content-Type: video/mp4\r\n\r\n').encode() + args.media.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
    status, response = raw('/media/upload', token, 'POST', payload, 'multipart/form-data; boundary=' + boundary)
    response = json.loads(response)
    assert status == 200 and response['code'] == 0, 'real media upload failed'
    media_id = response['data']['id']
    wait_job(token, media_id, 'READY')
    imported = api('/knowledge/ingest/import-transcript', token, 'POST', {
        'mediaId': media_id, 'goal': 'archived-real-transcript:' + args.media_key, 'segments': video['segments']})
    segments = api(f'/knowledge/sources/media/{media_id}/segments', token)
    assert len(segments) == len(video['segments'])
    assert all((s['startMs'], s['endMs'], s['transcript']) == (v['startMs'], v['endMs'], v['text'])
               for s, v in zip(segments, video['segments']))
    space = api('/knowledge/spaces', token)[0]['id']
    source_id = imported['sourceId']
    folders = [api(f'/knowledge/spaces/{space}/collections', token, 'POST', {'name': name + uuid.uuid4().hex[:4]})['id']
               for name in ('真实课程后端', '真实课程前端')]
    for folder in folders:
        api(f'/knowledge/sources/{source_id}/placements', token, 'POST', {'spaceId': space, 'collectionId': folder})
    query = '苍穹外卖'
    hits = [api('/knowledge/search', token, 'POST', {'spaceId': space, 'collectionId': folder,
            'query': query, 'strategy': 'keyword', 'topK': 5}) for folder in folders]
    assert all(any(h['sourceId'] == source_id for h in group) for group in hits)
    assert len({h['segmentId'] for h in hits[0]}) == len(hits[0])
    url = api(f'/media/playback?id={media_id}', token)
    # Presigned URLs stay in memory; no token or expiring URL enters the report.
    request = urllib.request.Request(url, headers={'Range': 'bytes=0-4095'})
    with urllib.request.urlopen(request) as response:
        assert response.status == 206 and response.headers.get('Content-Range')
        assert len(response.read()) == 4096
    decoded = subprocess.run(['ffmpeg', '-v', 'error', '-ss', '75', '-i', url, '-t', '3', '-f', 'null', '-'],
        capture_output=True)
    assert decoded.returncode == 0, 'remote media decoding failed'
    report = {'date': '2026-10-08', 'media_sha256': hashlib.sha256(args.media.read_bytes()).hexdigest(),
        'seed_sha256': hashlib.sha256(seed.read_bytes()).hexdigest(), 'mediaKey': args.media_key,
        'probe': probe, 'sourceId': source_id, 'mediaId': media_id, 'spaceId': space,
        'imported_raw_segments': len(segments), 'two_folder_retrieval': True,
        'range_playback': True, 'remote_decode_at_75_seconds': True,
        'native_asr_verified': False, 'real_model_quality_verified': False,
        'boundary': 'Real course bytes and archived ASR/OCR; deterministic fixture chunking/embedding; not production quality.'}
    target = root / 'eval/reports/real-media-smoke-20261008.json'
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'passed': True, 'report': str(target)}, ensure_ascii=False))

if __name__ == '__main__':
    main()
