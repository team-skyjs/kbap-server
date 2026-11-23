"""KB-998: one no-hint baseline. Offline by default."""
import argparse
import csv
import fcntl
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import urllib.request
from dataclasses import asdict, dataclass
from decimal import Decimal as D
from pathlib import Path
from shutil import which

ROOT = Path(__file__).resolve().parents[2]
STATE = Path.home()/'.kbap-eval'/'scan-hint-v1'
PIN = 'aa73dba2ae7d28b381199eb0779030b00c2a53c36c406affb86def470401f5b2'
CAP, RESERVE = D('15'), D('0.76')
MODEL = 'gpt-5.6-luna'
KINDS = {'MENU','PACKAGE_FRONT','INGREDIENT_LABEL','OTHER'}

@dataclass
class Snapshot:
    kind: str
    inp: int
    out: int
    model: str
    ms: int

    def cost(self, ceiling=False):
        if not (self.kind in KINDS and 0 < self.inp <= 1050000 and 0 < self.out <= 128000
                and self.ms >= 0 and re.fullmatch(MODEL.replace('.',r'\.')+r'(?:-\d{4}-\d{2}-\d{2})?',self.model)):
            raise ValueError('모델·usage·결과를 확인할 수 없어 중단')
        long = self.inp > 272000
        inp = D('.2') * (2 if long else 1) * (D('1.25') if ceiling else 1)
        out = D('1.8') if long else D('1.2')
        return (self.inp*inp + self.out*out)/1000000

def durable(path, data, exclusive=False):
    with path.open('x' if exclusive else 'w') as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)

def results(root):
    return {f.stem:Snapshot(**json.loads(f.read_text())) for f in root.glob('*.result')}

def run(rows, root, call, verify, cap=CAP):
    done = results(root)
    unresolved = [p.stem for p in root.glob('*.pending') if p.stem not in done]
    if unresolved:
        raise RuntimeError('결제 여부 미확정: 자동 재호출 금지 '+str(unresolved))
    spent = sum((r.cost(True) for r in done.values()),D(0))
    for index, row in enumerate(rows, 1):
        key = row['key']
        if key in done:
            continue
        if spent + RESERVE > cap:
            print('예산 중단: 다음 요청 최대 비용을 확보할 수 없음')
            return
        verify(row)
        durable(root/(key+'.pending'), 'reserved '+str(RESERVE), exclusive=True)
        result = call(row)
        actual = result.cost(True)
        if actual > RESERVE:
            raise RuntimeError('단가/모델 한도 점검 필요')
        durable(root/(key+'.result'),json.dumps(asdict(result)),exclusive=True)
        spent += actual
        if index % 25 == 0:
            print(f"{index}/{len(rows)} 완료 · 누적 비용 상계 USD {spent:.4f}", flush=True)

def summarize(rows,root):
    seen = results(root)
    paired = [(r,seen[r['key']]) for r in rows if r['key'] in seen]
    menus = [(r,s) for r,s in paired if r['truth']=='MENU']
    other = [(r,s) for r,s in paired if r['truth']!='MENU']
    missed = [r for r,s in menus if s.kind!='MENU']
    false = sum(s.kind=='MENU' for r,s in other)
    xs,s22 = [sum(r[k]=='1' for r in missed) for k in ('xs_menu','s22_menu')]
    n = len(paired)
    pending = sum(p.stem not in seen for p in root.glob('*.pending'))
    def ratio(a,b):
        return f'{a}/{b} ({100*a/b:.2f}%)' if b else '미측정'
    latency = sorted(s.ms for r,s in paired)
    q = lambda p: str(latency[math.ceil(n*p)-1])+' ms' if n else '미측정'
    cost = sum((s.cost() for r,s in paired),D(0))
    spent = sum((s.cost(True) for r,s in paired),D(0))
    decision = '판정 보류 — 표본 미완료'
    if n==1000 and not pending:
        decision = ('중단 — 두 기기 모두 최대 5건 미만' if max(xs,s22)<5 else
                    '중단 권고 — 메뉴판 놓침률 1% 미만' if len(missed)/len(menus)<.01 else
                    '예진 판단 — 다음 기기만 A/B 검토 가능: '+', '.join(k for k,c in [('XS',xs),('S22+',s22)] if c>=5))
    lines = ['# 무힌트 기준선',f'완료 {n}/{len(rows)} · 응답/과금 미확정 {pending} · {decision}',
             '', '| 지표 | 현재까지 확인한 결과 |','|---|---:|',
             f'| 메뉴판 놓침 | {ratio(len(missed),len(menus))} |',f'| 메뉴판 오인 | {ratio(false,len(other))} |',
             f'| 최대 개선 후보 XS / S22+ (동일 사진 중복 합산 없음) | {xs} / {s22} |',
             f'| 표준 단가 환산 합계 / 완료 장당 (USD) | {cost} / {cost/n if n else "미측정"} |',
             f'| 비용 보수 상계 + 미확정 예약 (USD) | {spent} + {pending*RESERVE} |',
             f'| read 지연 p50 / p95 | {q(.50)} / {q(.95)} |']
    return '\n'.join(lines)+'\n'

def report(rows,root):
    (root/'summary.md').write_text(summarize(rows,root))
    seen=results(root)
    with (root/'predictions.csv').open('w',newline='') as f:
        w=csv.writer(f)
        w.writerow(['key','sha256','truth','xs_menu','s22_menu','status','kind','input','output','model','ms','usd_standard'])
        for row in rows:
            key=row['key']
            s=seen.get(key)
            state='complete' if s else 'unknown' if (root/(key+'.pending')).exists() else 'unattempted'
            w.writerow([row[k] for k in ('key','sha256','truth','xs_menu','s22_menu')]+[state]+
                       ([s.kind,s.inp,s.out,s.model,s.ms,s.cost()] if s else ['']*6))

def verify(row):
    with urllib.request.urlopen(row['url'],timeout=25) as response:
        if response.geturl()!=row['url'] or hashlib.sha256(response.read(2*1024*1024+1)).hexdigest()!=row['sha256']:
            raise ValueError('CDN 사진 불일치: '+row['key'])

def worker_call(p,row):
    p.stdin.write(row['imagePath']+'\n')
    p.stdin.flush()
    for line in p.stdout:
        if line.startswith('RESULT\t'):
            _,kind,i,o,m,ms=line.strip().split('\t')
            return Snapshot(kind,int(i),int(o),m,int(ms))
    raise RuntimeError('서버 판독기 중단: 이 사진은 자동 재호출하지 않습니다')

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('manifest',type=Path)
    ap.add_argument('--live',action='store_true')
    args=ap.parse_args()
    if hashlib.sha256(args.manifest.read_bytes()).hexdigest()!=PIN:
        ap.error('승인된 paths.csv 해시 불일치')
    with args.manifest.open(newline='') as f:
        rows=list(csv.DictReader(f))
    menu=sum(r['truth']=='MENU' for r in rows)
    xs,s22=(sum(int(r[k]) for r in rows) for k in ('xs_menu','s22_menu'))
    print(f'사진 {len(rows)}장 · 메뉴판 {menu} / 비메뉴판 {len(rows)-menu} · XS 후보 {xs} / S22+ 후보 {s22}')
    print('예상 USD 1.00 (가정: 장당 입력 2,000 / 출력 500토큰, 캐시 할인 제외); 상한 USD 15')
    if not args.live:
        print('dry-run: 유료 호출 0 · 이미지 전송 0 · DB 연결 0')
        return
    if not os.environ.get('OPENAI_API_KEY'):
        ap.error('OPENAI_API_KEY 환경 변수 필요')
    cp=(ROOT/'common/build/scan-hint-classpath').read_text()
    STATE.mkdir(mode=0o700,parents=True,exist_ok=True)
    with (STATE/'lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        ident = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()+' '+PIN
        if subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],cwd=ROOT):
            ap.error('live는 변경 없는 커밋에서만 실행')
        binding=STATE/'identity'
        if binding.exists():
            if binding.read_text()!=ident:
                ap.error('기존 실행의 코드/사진 조합과 다릅니다')
        else:
            durable(binding,ident,exclusive=True)
        java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else which('java')
        with subprocess.Popen([java,'-cp',cp,'com.kbap.common.infra.llm.config.ScanBaselineWorker'],
                   stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True) as p:
            try:
                run(rows,STATE,lambda row:worker_call(p,row),verify)
            finally:
                p.stdin.close()
                report(rows,STATE)
                print((STATE/'summary.md').read_text())
                print(STATE/'summary.md')
        if len(results(STATE))!=len(rows):
            raise SystemExit('기준선 미완료: 도입 여부 판정 보류')

if __name__=='__main__':
    main()
