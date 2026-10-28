"""Analyze real deadline-policy deferral without promoting simulator timing to a release gate."""
from pathlib import Path
import argparse
import hashlib
import json
import random
import statistics

HERE=Path(__file__).resolve().parent
METRICS=('main_cpu_nanos','elapsed_nanos','frames')


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def percentile(values,p):
    values=sorted(values);point=(len(values)-1)*p;lo=int(point);hi=min(lo+1,len(values)-1)
    return values[lo]+(values[hi]-values[lo])*(point-lo)


def validate_block(block):
    samples=block['samples']
    if set(samples)!={'budget-on','budget-off'} or sorted(block['order'])!=['budget-off','budget-on']:
        raise ValueError('Missing group or invalid order')
    if samples['budget-on']['checksum']!=samples['budget-off']['checksum']:
        raise ValueError('Pair has different workload checksum')
    for name in ('budget-off','budget-on'):
        s=samples[name];ticks=s['ticks']
        if s['completed']!=48 or s['pending']!=0 or s['rejected']!=0 or s['frames']!=len(ticks) or s['driver_requests']!=len(ticks):
            raise ValueError('Loss, rejection or frame mismatch')
        if [r['sequence'] for r in ticks]!=list(range(1,len(ticks)+1)) or sum(r['consumed'] for r in ticks)!=48:
            raise ValueError('Frame order or completed work mismatch')
        completed=0
        for tick in ticks:
            if not 0<=tick['consumed']<=24:raise ValueError('Per-frame quota exceeded')
            completed+=tick['consumed']
            if tick['completed_total']!=completed or tick['pending']!=48-completed:raise ValueError('Queue state mismatch')
            if name=='budget-off':
                if any(tick[k] is not None for k in ('deadline_nanos','initial_budget_nanos','remaining_budget_nanos')) or tick['budget_deferred']:
                    raise ValueError('Disabled group invented a budget')
            else:
                if any(tick[k] is None or tick[k]<0 for k in ('deadline_nanos','initial_budget_nanos','remaining_budget_nanos')):
                    raise ValueError('Enabled group lacks real platform budget')
        actual_deferred=[t for t in ticks if t['budget_deferred']]
        if s['budget_deferral_events']!=len(actual_deferred) or s['budget_backlog_sum']!=sum(t['pending'] for t in actual_deferred):
            raise ValueError('Budget deferral observations inconsistent')
        expected_quota=sum(t['consumed']==24 and t['pending']>0 and not t['budget_deferred'] for t in ticks)
        if s['quota_deferral_events']!=expected_quota:raise ValueError('Quota deferral observations inconsistent')
        if name=='budget-on':
            if s['deadline_overruns']!=sum(t['end_nanos']>t['deadline_nanos'] for t in ticks):raise ValueError('Deadline overrun count mismatch')
            if s['reserve_breaches']!=sum(t['end_nanos']>t['deadline_nanos']-12000000 for t in ticks):raise ValueError('Reserve breach count mismatch')
        elif s['deadline_overruns'] is not None or s['reserve_breaches'] is not None:
            raise ValueError('Unknown fallback cannot report deadline outcomes')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('run',type=Path);args=parser.parse_args()
    run=args.run.resolve();allowed=(HERE/'results').resolve()
    if not run.is_relative_to(allowed) or run==allowed:raise SystemExit('Run directory outside probe')
    output=run/'analysis.json'
    if output.exists():raise SystemExit('Existing analysis is preserved')
    execution=json.loads((run/'execution.json').read_text(encoding='utf-8'))
    if execution['status']!='PASS' or len(execution['runs'])!=6 or len({x['pid'] for x in execution['runs']})!=6:
        raise ValueError('Six independent completed processes required')
    records=[];metrics={k:[] for k in METRICS};total_deferrals=0;total_backlog=0;total_overruns=0;total_breaches=0
    for record in execution['runs']:
        index=record['index'];path=run/f'run-{index}'/'raw.json'
        if sha(path)!=record['raw_sha256']:raise ValueError('Raw sample hash changed')
        data=json.loads(path.read_text(encoding='utf-8'))
        if (data['kind']!='p2_budget_policy' or data['process_index']!=index or data['process_pid']!=record['pid']
            or data['process_run_id']!=record['run_id'] or data['identity']!=execution['identity']
            or len(data['warmup'])!=4 or len(data['blocks'])!=20):raise ValueError('Identity or block count mismatch')
        for block in data['warmup']+data['blocks']:validate_block(block)
        if sum(b['order'][0]=='budget-on' for b in data['blocks'])!=10:raise ValueError('Unbalanced order')
        for metric in METRICS:
            pairs=[(b['samples']['budget-off'][metric],b['samples']['budget-on'][metric]) for b in data['blocks']]
            if any(a<0 or b<0 for a,b in pairs):raise ValueError('Negative measurement')
            base=statistics.median(a for a,_ in pairs);new=statistics.median(b for _,b in pairs)
            delta=statistics.median(a-b for a,b in pairs);zeroes=sum(a==0 for a,_ in pairs)
            if base<=0:raise ValueError('Process baseline median zero')
            planned=None if zeroes else statistics.median(100*(a-b)/a for a,b in pairs)
            metrics[metric].append(dict(pid=data['process_pid'],baseline_median=base,candidate_median=new,
                paired_median_difference=delta,zero_baseline_samples=zeroes,planned_reduction_percent=planned))
        for block in data['blocks']:
            row=block['samples']['budget-on'];total_deferrals+=row['budget_deferral_events'];total_backlog+=row['budget_backlog_sum']
            total_overruns+=row['deadline_overruns'];total_breaches+=row['reserve_breaches']
        records.append(dict(index=index,pid=data['process_pid'],run_id=data['process_run_id'],raw_sha256=sha(path)))
    rng=random.Random(20261028);comparisons={}
    for name,rows in metrics.items():
        values=[r['paired_median_difference'] for r in rows]
        boots=[statistics.median(rng.choices(values,k=len(values))) for _ in range(20000)]
        item=dict(processes=rows,paired_median_difference=statistics.median(values),
                  bootstrap_difference_95=[percentile(boots,.025),percentile(boots,.975)],
                  zero_baseline_samples=sum(r['zero_baseline_samples'] for r in rows))
        planned=[r['planned_reduction_percent'] for r in rows]
        if all(x is not None for x in planned):
            b=[statistics.median(rng.choices(planned,k=len(planned))) for _ in range(20000)]
            item.update(planned_median_reduction_percent=statistics.median(planned),planned_bootstrap_95=[percentile(b,.025),percentile(b,.975)])
        else:item['planned_percent_status']='UNAVAILABLE_ZERO_BASELINE; all samples retained'
        comparisons[name]=item
    result=dict(status='ANALYZED' if total_deferrals else 'NOT_EXERCISED',performance_gate='BLOCKED',
        source_tree_sha256=execution['identity']['source_tree_sha256'],runtime_aar_sha256=execution['identity']['runtime_aar_sha256'],
        independent_unit='instrumentation process',independent_processes=6,measured_paired_blocks_per_process=20,
        same_items_per_sample=48,measured_total_items_per_group=6*20*48,
        budget_deferral_events=total_deferrals,budget_backlog_sum=total_backlog,
        deadline_overrun_frames=total_overruns,reserve_breach_frames=total_breaches,
        positive_reduction_means='less time or fewer frames in enabled group; negative means more',
        bootstrap='20000 fixed-seed process-median resamples; linear percentile',records=records,comparisons=comparisons,
        scope='Actual public Vsync and cooperative synthetic consumer on one emulator; callback completion, not frame presentation.',
        limitations=['No physical-performance or real-business-consumer claim','Started work cannot be preempted',
                     'Backlog count repeats tasks across deferral events','JIT/AOT, frequency and system load not controlled',
                     'No automatic scheduler time limit: only this cooperative consumer respects advice'])
    output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'performance_gate':result['performance_gate'],'deferral_events':total_deferrals,'output':str(output)},indent=2))

if __name__=='__main__':main()
