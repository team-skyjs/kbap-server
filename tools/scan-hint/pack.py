"""Prepare only the approved XS/S22 v1 bytes. Local I/O only."""
import csv
import hashlib
import io
import json
import re
import sys
import tarfile
from pathlib import Path

ROOT, OUT = map(Path, sys.argv[1:])
digest = lambda data: hashlib.sha256(data).hexdigest()
truth = (ROOT/'truth.json').read_bytes()
pairs = (ROOT/'paired.csv').read_bytes()
if (digest(truth), digest(pairs)) != (
    'adf5ff24fb2fb85e32a3f54ae8080654c6c1fcad05403600dce5842107e3549c',
    '39a8a791736def762d266105794167fbcba7de0c6680071863c9ebc4b07bc9de'):
    raise ValueError('승인된 v1 입력이 아닙니다')
pred = {row['key']: row for row in csv.DictReader(io.StringIO(pairs.decode()))}
photos = json.loads(truth)
if len(set(p['sha256'] for p in photos)) != 1000 or len(pred) != len(photos) or len(pred) != 1000:
    raise ValueError('1000장 고정 세트 오류')
OUT.mkdir(parents=True, exist_ok=True)
with tarfile.open(OUT/'scan-hint-v1.tar','w') as tar, (OUT/'paths.csv').open('w',newline='') as f:
    w = csv.writer(f, lineterminator='\n')
    w.writerow(['key','sha256','truth','xs_menu','s22_menu','imagePath','url'])
    checks = []
    for p in photos:
        key, h = p['key'], p['sha256']
        row = pred[key]
        image = (ROOT/'pool'/p['filename']).read_bytes()
        if digest(image) != h or row['truth'] != p['label'] or not re.fullmatch('[A-Za-z0-9_-]+',key):
            raise ValueError(key)
        filename = h+'.jpg'
        path = 'eval/scan-hint/v1/'+filename
        menu = lambda d: int(row[d]=='MENU' and float(row[d+'_score'])>=.98)
        w.writerow([key,h,p['label'],menu('xs'),menu('s22'),path,'https://d29c1cr2ng7w0.cloudfront.net/'+path])
        info = tarfile.TarInfo('images/'+filename)
        info.size = len(image)
        tar.addfile(info,io.BytesIO(image))
        checks.append(h+'  images/'+filename+'\n')
    data = ''.join(checks).encode()
    info = tarfile.TarInfo('SHA256SUMS')
    info.size = len(data)
    tar.addfile(info,io.BytesIO(data))
print('manifest_sha256',digest((OUT/'paths.csv').read_bytes()))
