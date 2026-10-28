package io.github.jichuo1.meguviz.p2budgetpolicy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler;
import io.github.jichuo1.meguviz.android.runtime.core.FrameTick;
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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Same finite work with actual deadline deferral versus unknown fixed-quota fallback. */
@RunWith(AndroidJUnit4.class)
public final class P2BudgetPolicyTest {
    private static final int WARMUP=4, BLOCKS=20, ITEMS=48, ITERATIONS=200000, QUOTA=24, MAX_FRAMES=120;
    private static final long RESERVE_NANOS=12_000_000L, SEED=20261028L;
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private Activity activity;
    private volatile long blackhole;

    @Test public void pairedBudgetPolicy() throws Exception {
        Bundle args=InstrumentationRegistry.getArguments();
        int processIndex=Integer.parseInt(args.getString("process_index","-1"));
        String runId=args.getString("process_run_id","");
        assertTrue(processIndex>=0 && processIndex<6); UUID.fromString(runId);
        assertTrue("actual deadline comparison requires API33+",Build.VERSION.SDK_INT>=33);
        Intent intent=new Intent(instrumentation.getTargetContext(),ProbeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity=instrumentation.startActivitySync(intent);instrumentation.waitForIdleSync();
        try {
            JSONObject identity=new JSONObject(new String(readAll(activity.getAssets().open("identity.json")),StandardCharsets.UTF_8));
            JSONObject result=new JSONObject();
            result.put("schema_version",1).put("kind","p2_budget_policy").put("process_index",processIndex)
                    .put("process_run_id",runId).put("process_pid",Process.myPid()).put("seed",SEED)
                    .put("identity",identity).put("environment",environment())
                    .put("app_apk_sha256",fileSha(new File(activity.getApplicationInfo().sourceDir)))
                    .put("test_apk_sha256",fileSha(new File(instrumentation.getContext().getApplicationInfo().sourceDir)))
                    .put("workload",new JSONObject().put("warmup_blocks",WARMUP).put("measured_blocks",BLOCKS)
                            .put("items",ITEMS).put("iterations_per_item",ITERATIONS).put("quota",QUOTA)
                            .put("max_frames",MAX_FRAMES).put("reserve_nanos",RESERVE_NANOS));
            boolean[] ordering=orderSchedule(processIndex);JSONArray warmup=new JSONArray(),measured=new JSONArray();
            for(int block=-WARMUP;block<BLOCKS;block++) {
                boolean reverse=block<0?((block+processIndex)&1)!=0:ordering[block];
                JSONObject value=block(block,reverse);
                if(block<0)warmup.put(value);else measured.put(value);
            }
            result.put("warmup",warmup).put("blocks",measured).put("blackhole",blackhole);
            File base=activity.getExternalFilesDir(null);assertNotNull(base);
            File directory=new File(base,"p2-budget-policy-results");assertTrue(directory.isDirectory() || directory.mkdirs());
            File output=new File(directory,"p2-"+runId+".json");assertFalse("preserve prior result",output.exists());
            try(FileOutputStream stream=new FileOutputStream(output)) {
                stream.write((result.toString()+"\n").getBytes(StandardCharsets.UTF_8));stream.getFD().sync();
            }
            Bundle status=new Bundle();status.putString("p2_result_path",output.getAbsolutePath());
            status.putString("p2_result_sha256",fileSha(output));status.putString("p2_run_id",runId);
            instrumentation.sendStatus(2,status);
        } finally {instrumentation.runOnMainSync(() -> activity.finish());}
    }

    private boolean[] orderSchedule(int processIndex) {
        boolean[] result=new boolean[BLOCKS];for(int i=0;i<BLOCKS;i++) result[i]=(i&1)==0;
        Random random=new Random(SEED ^ ((long)processIndex<<32));
        for(int i=BLOCKS-1;i>0;i--){int j=random.nextInt(i+1);boolean old=result[i];result[i]=result[j];result[j]=old;}
        return result;
    }
    private JSONObject block(int block,boolean reverse) throws Exception {
        int seed=block+1000;JSONArray order=new JSONArray();JSONObject samples=new JSONObject();
        JSONObject first=sample(reverse,seed);order.put(reverse?"budget-on":"budget-off");
        JSONObject second=sample(!reverse,seed);order.put(reverse?"budget-off":"budget-on");
        samples.put(reverse?"budget-on":"budget-off",first).put(reverse?"budget-off":"budget-on",second);
        assertEquals(first.getLong("checksum"),second.getLong("checksum"));
        assertEquals(ITEMS,first.getInt("completed"));assertEquals(ITEMS,second.getInt("completed"));
        assertEquals(0,first.getInt("pending"));assertEquals(0,second.getInt("pending"));
        return new JSONObject().put("block",block).put("order",order).put("samples",samples).put("gc_count",gcCount());
    }

    private JSONObject sample(boolean enabled,int seed) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        AtomicReference<JSONObject> captured=new AtomicReference<>();AtomicReference<FrameScheduler> scheduler=new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                MainThreadDispatcher owner=new MainThreadDispatcher();CountingDriver driver=new CountingDriver(new BudgetFrameDriver(owner,enabled));
                FrameScheduler current=new FrameScheduler(owner,driver,error -> {failure.set(error);done.countDown();},QUOTA);
                scheduler.set(current);
                long[] cpuStart={0},elapsedStart={0},checksum={0};int[] next={0},frames={0},budgetDeferrals={0},quotaDeferrals={0};
                long[] deferredBacklogSum={0};int[] deadlineOverruns={0},reserveBreaches={0};boolean[] seen=new boolean[ITEMS];
                JSONArray ticks=new JSONArray();FrameScheduler.FrameChannel[] channel=new FrameScheduler.FrameChannel[1];
                channel[0]=current.openChannel(new Object(),(tick,quota) -> {
                    int consumed=0;
                    try {
                        assertEquals(QUOTA,quota);frames[0]++;
                        if(frames[0]>MAX_FRAMES)throw new AssertionError("bounded frame limit exceeded");
                        if(enabled){assertEquals("platform-vsync",tick.budgetSource());assertNotNull(tick.deadlineNanos());}
                        else{assertEquals("unknown",tick.budgetSource());assertNull(tick.deadlineNanos());}
                        long frameStart=System.nanoTime();Long initial=tick.workBudgetNanos(frameStart,RESERVE_NANOS);
                        boolean blockedByBudget=false;
                        while(next[0]<ITEMS && consumed<quota) {
                            long before=System.nanoTime();Long budget=tick.workBudgetNanos(before,RESERVE_NANOS);
                            if(enabled && (budget==null || budget<=0)) {
                                blockedByBudget=true;budgetDeferrals[0]++;deferredBacklogSum[0]+=ITEMS-next[0];break;
                            }
                            int index=next[0];assertFalse("work item duplicated",seen[index]);seen[index]=true;
                            checksum[0]+=work(seed,index);next[0]++;consumed++;
                        }
                        long frameEnd=System.nanoTime();Long remaining=tick.workBudgetNanos(frameEnd,RESERVE_NANOS);
                        if(enabled && frameEnd>tick.deadlineNanos())deadlineOverruns[0]++;
                        if(enabled && frameEnd>tick.deadlineNanos()-RESERVE_NANOS)reserveBreaches[0]++;
                        if(next[0]<ITEMS && !blockedByBudget && consumed==quota)quotaDeferrals[0]++;
                        JSONObject row=new JSONObject().put("sequence",tick.sequence()).put("frame_nanos",tick.frameTimeNanos())
                                .put("start_nanos",frameStart).put("end_nanos",frameEnd).put("consumed",consumed)
                                .put("completed_total",next[0]).put("pending",ITEMS-next[0]).put("budget_deferred",blockedByBudget)
                                .put("deadline_nanos",tick.deadlineNanos()==null?JSONObject.NULL:tick.deadlineNanos())
                                .put("initial_budget_nanos",initial==null?JSONObject.NULL:initial)
                                .put("remaining_budget_nanos",remaining==null?JSONObject.NULL:remaining);
                        ticks.put(row);
                        if(next[0]<ITEMS)channel[0].requestFrame();
                        else {
                            for(boolean item:seen)assertTrue("work item missing",item);
                            blackhole=checksum[0];
                            JSONObject data=new JSONObject().put("main_cpu_nanos",Debug.threadCpuTimeNanos()-cpuStart[0])
                                    .put("elapsed_nanos",SystemClock.elapsedRealtimeNanos()-elapsedStart[0])
                                    .put("completed",next[0]).put("pending",ITEMS-next[0]).put("checksum",checksum[0])
                                    .put("frames",frames[0]).put("driver_requests",driver.requests).put("rejected",driver.rejections)
                                    .put("budget_deferral_events",budgetDeferrals[0]).put("budget_backlog_sum",deferredBacklogSum[0])
                                    .put("quota_deferral_events",quotaDeferrals[0])
                                    .put("deadline_overruns",enabled?deadlineOverruns[0]:JSONObject.NULL)
                                    .put("reserve_breaches",enabled?reserveBreaches[0]:JSONObject.NULL).put("ticks",ticks);
                            captured.set(data);done.countDown();
                        }
                    }catch(Throwable error){failure.set(error);done.countDown();}
                    return consumed;
                },QUOTA);
                cpuStart[0]=Debug.threadCpuTimeNanos();elapsedStart[0]=SystemClock.elapsedRealtimeNanos();channel[0].requestFrame();
            }catch(Throwable error){failure.set(error);done.countDown();}
        });
        try {
            assertTrue("frame/deferral timeout",done.await(15,TimeUnit.SECONDS));
            if(failure.get()!=null)throw new AssertionError(failure.get());
            JSONObject data=captured.get();assertNotNull(data);
            assertEquals(ITEMS,data.getInt("completed"));assertEquals(0,data.getInt("pending"));
            assertEquals(data.getInt("frames"),data.getInt("driver_requests"));assertEquals(0,data.getInt("rejected"));
            assertTrue(data.getLong("main_cpu_nanos")>=0 && data.getLong("elapsed_nanos")>0);
            return data;
        }finally{instrumentation.runOnMainSync(() -> {if(scheduler.get()!=null)scheduler.get().close();});}
    }
    private static long work(int seed,int index){long x=((long)seed<<32)^index^0x5deece66dL;for(int i=0;i<ITERATIONS;i++)x=(x*0x5deece66dL+0xbL)^(x>>>17);return x;}
    private String gcCount(){String value=Debug.getRuntimeStat("art.gc.gc-count");return value==null?"unknown":value;}
    @SuppressWarnings("deprecation") private JSONObject environment() throws Exception {
        Display display=((WindowManager)activity.getSystemService(Activity.WINDOW_SERVICE)).getDefaultDisplay();
        Display.Mode active=display.getMode();JSONArray modes=new JSONArray();
        for(Display.Mode mode:display.getSupportedModes()) modes.put(mode(mode));
        PowerManager power=(PowerManager)activity.getSystemService(Activity.POWER_SERVICE);
        Object thermal=Build.VERSION.SDK_INT>=29?power.getCurrentThermalStatus():JSONObject.NULL;
        return new JSONObject().put("api",Build.VERSION.SDK_INT).put("fingerprint",Build.FINGERPRINT)
                .put("target_sdk",activity.getApplicationInfo().targetSdkVersion).put("abis",new JSONArray(Build.SUPPORTED_ABIS))
                .put("refresh_hz",display.getRefreshRate()).put("active_display_mode",mode(active)).put("supported_display_modes",modes)
                .put("thermal_status",thermal).put("jit_aot","unknown_no_forced_compilation");
    }
    private static JSONObject mode(Display.Mode mode)throws Exception{return new JSONObject().put("id",mode.getModeId())
            .put("width_px",mode.getPhysicalWidth()).put("height_px",mode.getPhysicalHeight()).put("refresh_hz",mode.getRefreshRate());}
    private static byte[] readAll(InputStream input)throws Exception{try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    private static String fileSha(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte b:d.digest())out.append(String.format("%02x",b&0xff));return out.toString();}
    private static final class CountingDriver implements FrameDriver {
        private final FrameDriver delegate;int requests,rejections;
        CountingDriver(FrameDriver delegate){this.delegate=delegate;}
        @Override public boolean request(Callback callback){requests++;boolean accepted=delegate.request(callback);if(!accepted)rejections++;return accepted;}
        @Override public void remove(Callback callback){delegate.remove(callback);}
    }
}
