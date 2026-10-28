"""Analyze all paired samples by independent process, without promoting a gate."""
from pathlib import Path
import argparse
import hashlib
import json
import random
import statistics

HERE=Path(__file__).resolve().parent
PAIRS={
    'frame_on_vs_off':('frame-off','frame-on',('main_cpu_nanos','elapsed_nanos','setup_cpu_nanos')),
    'cold_retiring_vs_direct':('cold-direct','cold-retiring',('cpu_nanos','elapsed_nanos')),
    'single_retiring_vs_direct':('single-direct','single-retiring',('cpu_nanos','elapsed_nanos')),
    'four_retiring_vs_direct':('four-direct','four-retiring',('cpu_nanos','elapsed_nanos')),
}


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def percentile(values,fraction):
    values=sorted(values); position=(len(values)-1)*fraction; lo=int(position); hi=min(lo+1,len(values)-1)
    return values[lo]+(values[hi]-values[lo])*(position-lo)


def validate_block(block):
    if set(block['samples'])!={x for base,new,_ in PAIRS.values() for x in (base,new)}:
        raise ValueError('Missing or unexpected measured group')
    if len(block['order'])!=8 or len(set(block['order']))!=8: raise ValueError('Invalid block order')
    s=block['samples']
    for base,new,_ in PAIRS.values():
        if s[base]['checksum']!=s[new]['checksum']: raise ValueError('Paired workload checksum mismatch')
    for name in ('frame-off','frame-on'):
        x=s[name]
        if (x['frames']!=8 or x['driver_requests']!=8 or x['work_operations']!=8*2048 or len(x['ticks'])!=8):
            raise ValueError('Frame work differs from registered protocol')
        if [t['sequence'] for t in x['ticks']]!=list(range(1,9)):raise ValueError('Frame sequence mismatch')
        expected='unknown' if name=='frame-off' else 'platform-vsync'
        if any(t['source']!=expected for t in x['ticks']): raise ValueError('Frame metadata source mismatch')
    for name in ('cold-direct','cold-retiring'):
        x=s[name]
        if (x['constructions'],x['bindings'],x['releases'])!=(32,32,32):raise ValueError('Cold work mismatch')
    for label,workers in (('single',1),('four',4)):
        for mode in ('direct','retiring'):
            x=s[label+'-'+mode];target=12000*workers
            if (x['workers']!=workers or x['attempts']!=target or x['successes']!=target or x['rejected']!=0
                    or x['releases']!=1 or len(x['worker_cpu_nanos'])!=workers): raise ValueError('Steady work mismatch')
            if x['leases_closed']!=(target if mode=='retiring' else 0): raise ValueError('Lease cleanup mismatch')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('run',type=Path);args=parser.parse_args()
    run=args.run.resolve();allowed=(HERE/'results').resolve()
    if not run.is_relative_to(allowed) or run==allowed:raise SystemExit('Run directory outside this probe')
    output=run/'analysis.json'
    if output.exists():raise SystemExit('Existing analysis is preserved')
    execution=json.loads((run/'execution.json').read_text(encoding='utf-8'))
    if execution['status']!='PASS' or len(execution['runs'])!=6:raise ValueError('Six complete runs required')
    if len({r['pid'] for r in execution['runs']})!=6:raise ValueError('Distinct process IDs required')
    groups={name:{metric:[] for metric in metrics} for name,(_,_,metrics) in PAIRS.items()}
    records=[]
    for run_record in execution['runs']:
        index=run_record['index'];path=run/f'run-{index}'/'raw.json'
        if sha(path)!=run_record['raw_sha256']:raise ValueError('Raw sample SHA changed')
        data=json.loads(path.read_text(encoding='utf-8'))
        if (data['process_index']!=index or data['process_pid']!=run_record['pid']
                or data['process_run_id']!=run_record['run_id'] or data['identity']!=execution['identity']
                or len(data['warmup'])!=4 or len(data['blocks'])!=20):raise ValueError('Raw identity/shape mismatch')
        for block in data['warmup']+data['blocks']:validate_block(block)
        for name,(base,new,metrics) in PAIRS.items():
            ordering=[b['order'].index(new)<b['order'].index(base) for b in data['blocks']]
            if sum(ordering)!=10:raise ValueError('Pair order is not balanced')
            for metric in metrics:
                pairs=[(b['samples'][base][metric],b['samples'][new][metric]) for b in data['blocks']]
                if any(a<=0 or b<0 for a,b in pairs):raise ValueError('Invalid timing value')
                reductions=[100.0*(a-b)/a for a,b in pairs]
                groups[name][metric].append(dict(pid=data['process_pid'],paired_median_reduction_percent=statistics.median(reductions),
                    baseline_median_nanos=statistics.median(a for a,_ in pairs),candidate_median_nanos=statistics.median(b for _,b in pairs)))
        records.append(dict(index=index,pid=data['process_pid'],run_id=data['process_run_id'],raw_sha256=sha(path)))
    comparisons={};rng=random.Random(20261028)
    for name,metrics in groups.items():
        comparisons[name]={}
        for metric,rows in metrics.items():
            values=[r['paired_median_reduction_percent'] for r in rows]
            bootstrap=[statistics.median(rng.choices(values,k=len(values))) for _ in range(20000)]
            comparisons[name][metric]=dict(processes=rows,median_reduction_percent=statistics.median(values),
                bootstrap_process_median_95_interval_percent=[percentile(bootstrap,.025),percentile(bootstrap,.975)])
    result=dict(status='ANALYZED',performance_gate='BLOCKED',independent_unit='instrumentation process',
        independent_processes=6,paired_blocks_per_process=20,bootstrap='20000 fixed-seed resamples of process medians',
        positive_reduction_means='less time in candidate; negative means more time',
        scope='Emulator-only CPU and monotonic elapsed; frame end is last library callback, not draw/GPU/presentation.',
        limitations=['No physical performance acceptance','JIT/AOT, frequency and OS load not controlled',
                     'Frame work is fixed and ignores advisory budget; no business workload or savings claim',
                     'Direct and retiring groups provide different lifetime guarantees'],
        execution_sha256=sha(run/'execution.json'),records=records,comparisons=comparisons)
    output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'performance_gate':result['performance_gate'],'processes':6,'output':str(output)},indent=2))

if __name__=='__main__':main()
