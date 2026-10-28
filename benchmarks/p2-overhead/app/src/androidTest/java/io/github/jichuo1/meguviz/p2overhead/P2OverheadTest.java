package io.github.jichuo1.meguviz.p2overhead;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jichuo1.meguviz.android.runtime.core.AdapterRegistry;
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler;
import io.github.jichuo1.meguviz.android.runtime.core.FrameTick;
import io.github.jichuo1.meguviz.android.runtime.core.RetiringRegistry;
import io.github.jichuo1.meguviz.android.runtime.platform.BudgetFrameDriver;
import io.github.jichuo1.meguviz.android.runtime.platform.MainThreadDispatcher;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Current-runtime matched-work measurements; the result never approves a performance gate. */
@RunWith(AndroidJUnit4.class)
public final class P2OverheadTest {
    private static final int WARMUP=4, BLOCKS=20, FRAMES=8, FRAME_WORK=2048, COLD=32, OPS=12000;
    private static final long RESERVE_NANOS=1_000_000L, SEED=20261028L;
    private static final String CAPABILITY="render", VALUE="provider";
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private volatile long blackhole;
    private Activity activity;

    @Test public void pairedBudgetAndRetirement() throws Exception {
        Bundle args=InstrumentationRegistry.getArguments();
        int processIndex=Integer.parseInt(args.getString("process_index", "-1"));
        String runId=args.getString("process_run_id", "");
        assertTrue(processIndex>=0 && processIndex<6); UUID.fromString(runId);
        Intent intent=new Intent(instrumentation.getTargetContext(),ProbeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity=instrumentation.startActivitySync(intent); instrumentation.waitForIdleSync();
        try {
            JSONObject identity=new JSONObject(new String(readAll(activity.getAssets().open("identity.json")),StandardCharsets.UTF_8));
            JSONObject result=new JSONObject();
            result.put("schema_version",1).put("kind","p2_paired_overhead").put("process_index",processIndex)
                    .put("process_run_id",runId).put("process_pid",Process.myPid()).put("seed",SEED)
                    .put("identity",identity).put("environment",environment())
                    .put("app_apk_sha256",fileSha(new File(activity.getApplicationInfo().sourceDir)))
                    .put("test_apk_sha256",fileSha(new File(instrumentation.getContext().getApplicationInfo().sourceDir)))
                    .put("workload",new JSONObject().put("warmup_blocks",WARMUP).put("measured_blocks",BLOCKS)
                            .put("frames_per_sample",FRAMES).put("operations_per_frame",FRAME_WORK)
                            .put("cold_constructions",COLD).put("operations_per_worker",OPS)
                            .put("reserve_nanos",RESERVE_NANOS));
            JSONArray warmup=new JSONArray(), measured=new JSONArray();
            boolean[][] ordering=orderSchedule(processIndex);
            for (int block=-WARMUP;block<BLOCKS;block++) {
                JSONObject measurement=block(block,processIndex,ordering);
                if (block<0) warmup.put(measurement); else measured.put(measurement);
            }
            result.put("warmup",warmup).put("blocks",measured).put("blackhole",blackhole);
            File base=activity.getExternalFilesDir(null); assertNotNull("external app files unavailable",base);
            File directory=new File(base,"p2-overhead-results");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            File output=new File(directory,"p2-"+runId+".json");
            assertFalse("preserve previous result",output.exists());
            try (FileOutputStream stream=new FileOutputStream(output)) {
                stream.write((result.toString()+"\n").getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            Bundle status=new Bundle(); status.putString("p2_result_path",output.getAbsolutePath());
            status.putString("p2_result_sha256",fileSha(output)); status.putString("p2_run_id",runId);
            instrumentation.sendStatus(2,status);
        } finally { instrumentation.runOnMainSync(() -> activity.finish()); }
    }

    private boolean[][] orderSchedule(int processIndex) {
        boolean[][] result=new boolean[4][BLOCKS];
        for (int pair=0;pair<result.length;pair++) {
            for(int i=0;i<BLOCKS;i++) result[pair][i]=(i&1)==0;
            Random random=new Random(SEED ^ ((long)processIndex<<32) ^ pair);
            for(int i=BLOCKS-1;i>0;i--) {
                int j=random.nextInt(i+1); boolean value=result[pair][i];
                result[pair][i]=result[pair][j]; result[pair][j]=value;
            }
        }
        return result;
    }

    private JSONObject block(int block,int processIndex,boolean[][] ordering) throws Exception {
        int seed=block+1000;
        JSONObject result=new JSONObject(), samples=new JSONObject(); JSONArray order=new JSONArray();
        for (int pair=0;pair<4;pair++) {
            boolean reverse=block<0?((block+processIndex+pair)&1)!=0:ordering[pair][block];
            if (pair==0) {
                JSONObject a=frame(reverse,seed); order.put(reverse?"frame-on":"frame-off");
                JSONObject b=frame(!reverse,seed); order.put(reverse?"frame-off":"frame-on");
                samples.put(reverse?"frame-on":"frame-off",a).put(reverse?"frame-off":"frame-on",b);
                assertEquals(a.getLong("checksum"),b.getLong("checksum"));
            } else if (pair==1) {
                JSONObject a=cold(reverse,seed); order.put(reverse?"cold-retiring":"cold-direct");
                JSONObject b=cold(!reverse,seed); order.put(reverse?"cold-direct":"cold-retiring");
                samples.put(reverse?"cold-retiring":"cold-direct",a).put(reverse?"cold-direct":"cold-retiring",b);
                assertEquals(a.getLong("checksum"),b.getLong("checksum"));
            } else {
                int workers=pair==2?1:4; String prefix=workers==1?"single-":"four-";
                JSONObject a=steady(reverse,workers,seed); order.put(prefix+(reverse?"retiring":"direct"));
                JSONObject b=steady(!reverse,workers,seed); order.put(prefix+(reverse?"direct":"retiring"));
                samples.put(prefix+(reverse?"retiring":"direct"),a).put(prefix+(reverse?"direct":"retiring"),b);
                assertEquals(a.getLong("checksum"),b.getLong("checksum"));
            }
        }
        result.put("block",block).put("order",order).put("samples",samples).put("gc_count",gcCount());
        return result;
    }

    private JSONObject frame(boolean enabled,int seed) throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> failure=new AtomicReference<>();
        AtomicReference<JSONObject> captured=new AtomicReference<>(); AtomicReference<FrameScheduler> scheduler=new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                MainThreadDispatcher owner=new MainThreadDispatcher();
                long setupStart=Debug.threadCpuTimeNanos();
                CountingDriver driver=new CountingDriver(new BudgetFrameDriver(owner,enabled));
                FrameScheduler current=new FrameScheduler(owner,driver,error -> { failure.set(error); done.countDown(); },4);
                scheduler.set(current);
                long setupCpu=Debug.threadCpuTimeNanos()-setupStart;
                long[] cpuStart={0}, elapsedStart={0}, checksum={0}; int[] frames={0};
                JSONArray ticks=new JSONArray(); FrameScheduler.FrameChannel[] channel=new FrameScheduler.FrameChannel[1];
                channel[0]=current.openChannel(new Object(),(tick,quota) -> {
                    try {
                        assertEquals(1,quota); frames[0]++;
                        Long budget=tick.workBudgetNanos(System.nanoTime(),RESERVE_NANOS);
                        if (enabled && Build.VERSION.SDK_INT>=33) {
                            assertEquals("platform-vsync",tick.budgetSource()); assertNotNull(tick.deadlineNanos());
                            assertNotNull(tick.expectedPresentationNanos()); assertNotNull(budget); assertTrue(budget>=0);
                        } else { assertEquals("unknown",tick.budgetSource()); assertNull(budget); }
                        long work=work(seed,frames[0],FRAME_WORK); checksum[0]+=work;
                        JSONObject item=new JSONObject().put("sequence",tick.sequence()).put("frame_nanos",tick.frameTimeNanos())
                                .put("source",tick.budgetSource()).put("deadline_nanos",tick.deadlineNanos()==null?JSONObject.NULL:tick.deadlineNanos())
                                .put("presentation_nanos",tick.expectedPresentationNanos()==null?JSONObject.NULL:tick.expectedPresentationNanos())
                                .put("budget_nanos",budget==null?JSONObject.NULL:budget);
                        ticks.put(item);
                        if (frames[0]<FRAMES) channel[0].requestFrame();
                        else {
                            blackhole=checksum[0];
                            captured.set(new JSONObject().put("main_cpu_nanos",Debug.threadCpuTimeNanos()-cpuStart[0])
                                    .put("elapsed_nanos",SystemClock.elapsedRealtimeNanos()-elapsedStart[0])
                                    .put("setup_cpu_nanos",setupCpu).put("frames",frames[0])
                                    .put("work_operations",frames[0]*FRAME_WORK).put("driver_requests",driver.requests)
                                    .put("checksum",checksum[0]).put("ticks",ticks));
                            done.countDown();
                        }
                    } catch (Throwable error) { failure.set(error); done.countDown(); }
                    return 1;
                },1);
                cpuStart[0]=Debug.threadCpuTimeNanos(); elapsedStart[0]=SystemClock.elapsedRealtimeNanos();
                channel[0].requestFrame();
            } catch (Throwable error) { failure.set(error); done.countDown(); }
        });
        try {
            assertTrue("frame callback timeout",done.await(8,TimeUnit.SECONDS));
            if (failure.get()!=null) throw new AssertionError(failure.get());
            JSONObject data=captured.get(); assertNotNull(data);
            assertEquals(FRAMES,data.getInt("frames")); assertEquals(FRAMES,data.getInt("driver_requests"));
            assertEquals(FRAMES*FRAME_WORK,data.getInt("work_operations"));
            assertTrue(data.getLong("main_cpu_nanos")>=0 && data.getLong("elapsed_nanos")>0);
            return data;
        } finally { instrumentation.runOnMainSync(() -> { if(scheduler.get()!=null) scheduler.get().close(); }); }
    }

    private JSONObject cold(boolean retiring,int seed) throws Exception {
        AtomicInteger releases=new AtomicInteger(); long checksum=0;
        long elapsed=SystemClock.elapsedRealtimeNanos(), cpu=Debug.threadCpuTimeNanos();
        for (int i=0;i<COLD;i++) {
            AdapterRegistry<String> snapshot=registry();
            if (retiring) {
                RetiringRegistry<String> owner=new RetiringRegistry<>(snapshot,1,2,releases::incrementAndGet);
                RetiringRegistry.Lease<String> lease=owner.tryAcquire(); assertNotNull(lease);
                assertEquals(VALUE,lease.snapshot().bind(CAPABILITY)); checksum+=work(seed,i,16); lease.close();
                assertTrue(owner.close().tryRelease());
            } else {
                assertEquals(VALUE,snapshot.bind(CAPABILITY)); checksum+=work(seed,i,16); releases.incrementAndGet();
            }
        }
        long cpuUsed=Debug.threadCpuTimeNanos()-cpu, duration=SystemClock.elapsedRealtimeNanos()-elapsed;
        blackhole=checksum; assertEquals(COLD,releases.get());
        return new JSONObject().put("cpu_nanos",cpuUsed).put("elapsed_nanos",duration)
                .put("constructions",COLD).put("bindings",COLD).put("releases",releases.get()).put("checksum",checksum);
    }

    private JSONObject steady(boolean retiring,int workers,int seed) throws Exception {
        AdapterRegistry<String> snapshot=registry(); AtomicInteger released=new AtomicInteger();
        RetiringRegistry<String> owner=retiring?new RetiringRegistry<>(snapshot,workers,2,released::incrementAndGet):null;
        ExecutorService executor=Executors.newFixedThreadPool(workers); CountDownLatch ready=new CountDownLatch(workers),start=new CountDownLatch(1);
        List<Future<long[]>> futures=new ArrayList<>();
        try {
            for (int worker=0;worker<workers;worker++) {
                final int index=worker;
                futures.add(executor.submit(() -> {
                    ready.countDown(); if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    long cpu=Debug.threadCpuTimeNanos(), sum=0; int success=0,rejected=0,closed=0;
                    for(int i=0;i<OPS;i++) {
                        if(retiring) {
                            RetiringRegistry.Lease<String> lease=owner.tryAcquire();
                            if(lease==null) { rejected++; continue; }
                            try { if(!VALUE.equals(lease.snapshot().bind(CAPABILITY))) throw new AssertionError(); sum+=work(seed,index*OPS+i,2); success++; }
                            finally { lease.close(); closed++; }
                        } else {
                            if(!VALUE.equals(snapshot.bind(CAPABILITY))) throw new AssertionError();
                            sum+=work(seed,index*OPS+i,2); success++;
                        }
                    }
                    return new long[]{Debug.threadCpuTimeNanos()-cpu,sum,success,rejected,closed};
                }));
            }
            assertTrue("workers not ready",ready.await(5,TimeUnit.SECONDS));
            long wall=SystemClock.elapsedRealtimeNanos(); start.countDown();
            long cpu=0,sum=0,success=0,rejected=0,closed=0; JSONArray workerCpu=new JSONArray();
            for(Future<long[]> future:futures) {
                long[] row=future.get(30,TimeUnit.SECONDS); cpu+=row[0]; workerCpu.put(row[0]);
                sum+=row[1]; success+=row[2]; rejected+=row[3]; closed+=row[4];
            }
            long duration=SystemClock.elapsedRealtimeNanos()-wall;
            assertEquals((long)OPS*workers,success); assertEquals(0,rejected);
            if(retiring) { assertEquals(success,closed); assertTrue(owner.close().tryRelease()); }
            else released.incrementAndGet();
            assertEquals(1,released.get()); blackhole=sum;
            return new JSONObject().put("cpu_nanos",cpu).put("elapsed_nanos",duration).put("worker_cpu_nanos",workerCpu)
                    .put("workers",workers).put("attempts",(long)OPS*workers).put("successes",success)
                    .put("rejected",rejected).put("leases_closed",closed).put("releases",released.get()).put("checksum",sum);
        } finally {
            start.countDown(); executor.shutdownNow(); assertTrue("executor cleanup timeout",executor.awaitTermination(5,TimeUnit.SECONDS));
        }
    }

    private static AdapterRegistry<String> registry() {
        AdapterRegistry.Descriptor descriptor=new AdapterRegistry.Descriptor("sample","1",1,0,0,
                Collections.singleton(CAPABILITY),Collections.emptySet(),Collections.emptySet(),Collections.emptySet(),
                "any","sample","caller");
        return new AdapterRegistry<>(Collections.singletonList(new AdapterRegistry.Provider<>(descriptor,VALUE)),
                Collections.emptySet(),Collections.emptySet(),Collections.emptyMap());
    }
    private static long work(int seed,int index,int iterations) {
        long x=((long)seed<<32) ^ index ^ 0x5deece66dL;
        for(int i=0;i<iterations;i++) x=(x*0x5deece66dL+0xbL) ^ (x>>>17);
        return x;
    }
    private String gcCount() { String value=Debug.getRuntimeStat("art.gc.gc-count"); return value==null?"unknown":value; }
    @SuppressWarnings("deprecation") private JSONObject environment() throws Exception {
        float hz=((WindowManager)activity.getSystemService(Activity.WINDOW_SERVICE)).getDefaultDisplay().getRefreshRate();
        PowerManager power=(PowerManager)activity.getSystemService(Activity.POWER_SERVICE);
        Object thermal=Build.VERSION.SDK_INT>=29?power.getCurrentThermalStatus():JSONObject.NULL;
        return new JSONObject().put("api",Build.VERSION.SDK_INT).put("fingerprint",Build.FINGERPRINT)
                .put("target_sdk",activity.getApplicationInfo().targetSdkVersion).put("abis",new JSONArray(Build.SUPPORTED_ABIS))
                .put("refresh_hz",hz).put("thermal_status",thermal).put("jit_aot","unknown_no_forced_compilation");
    }
    private static byte[] readAll(InputStream input) throws Exception {
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int n; while((n=in.read(buffer))!=-1) out.write(buffer,0,n); return out.toByteArray();
        }
    }
    private static String fileSha(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)) { byte[] buffer=new byte[8192]; int n; while((n=in.read(buffer))!=-1) digest.update(buffer,0,n); }
        StringBuilder out=new StringBuilder(); for(byte b:digest.digest()) out.append(String.format("%02x",b & 0xff)); return out.toString();
    }
    private static final class CountingDriver implements FrameDriver {
        private final FrameDriver delegate; int requests;
        CountingDriver(FrameDriver delegate) { this.delegate=delegate; }
        @Override public boolean request(Callback callback) { requests++; return delegate.request(callback); }
        @Override public void remove(Callback callback) { delegate.remove(callback); }
    }
}
