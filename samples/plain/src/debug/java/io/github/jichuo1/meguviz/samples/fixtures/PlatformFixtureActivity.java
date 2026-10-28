package io.github.jichuo1.meguviz.samples.fixtures;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import io.github.jichuo1.meguviz.android.lifecycle.LifecycleAdapters;
import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler;
import io.github.jichuo1.meguviz.android.runtime.core.LifecycleScope;
import io.github.jichuo1.meguviz.android.runtime.platform.ChoreographerDriver;
import io.github.jichuo1.meguviz.android.runtime.platform.MainThreadDispatcher;
import io.github.jichuo1.meguviz.android.window.ViewScope;
import io.github.jichuo1.meguviz.android.window.WindowSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Debug-only native lifecycle fixture. No static Activity registry or system configuration writes. */
public final class PlatformFixtureActivity extends FragmentActivity {
    public static final int FRAGMENT_CONTAINER_ID = 0x00f01234;
    public MainThreadDispatcher dispatcher;
    public FrameScheduler scheduler;
    public WindowSession session;
    public LinearLayout root;
    public FrameLayout fragmentContainer;
    public EditText input;
    public ListView list;
    public final List<Throwable> errors = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        input = new EditText(this); input.setSingleLine(true); input.setHint("MVX input fixture");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(input, new LinearLayout.LayoutParams(-1, dp(56)));
        fragmentContainer = new FrameLayout(this); fragmentContainer.setId(FRAGMENT_CONTAINER_ID);
        root.addView(fragmentContainer, new LinearLayout.LayoutParams(-1, dp(72)));
        list = new ListView(this); root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); root.requestFocus();
        dispatcher = new MainThreadDispatcher();
        scheduler = new FrameScheduler(dispatcher, new ChoreographerDriver(dispatcher), errors::add, 64);
        session = WindowSession.open(dispatcher, scheduler, getWindow(), this, getPackageName(), WindowSession.Permission.OWN_WINDOW, errors::add);
        LifecycleAdapters.bindWindow(this, session);
    }

    public int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        try { if (session != null) session.close(); }
        finally { try { if (scheduler != null) scheduler.close(); } finally { super.onDestroy(); } }
    }

    public static final class ScopedFragment extends Fragment {
        public ViewScope scope;
        @Override public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle state) {
            TextView view = new TextView(requireContext()); view.setText("Fragment View lifecycle"); return view;
        }
        @Override public void onViewCreated(View view, Bundle state) {
            scope = ((PlatformFixtureActivity) requireActivity()).session.scope(view);
            LifecycleAdapters.bindFragmentView(this, scope);
        }
    }

    public static final class TrackingTextView extends TextView {
        private final Set<OnAttachStateChangeListener> listeners = Collections.newSetFromMap(new IdentityHashMap<>());
        public TrackingTextView(Context context) { super(context); }
        @Override public void addOnAttachStateChangeListener(OnAttachStateChangeListener listener) {
            listeners.add(listener); super.addOnAttachStateChangeListener(listener);
        }
        @Override public void removeOnAttachStateChangeListener(OnAttachStateChangeListener listener) {
            listeners.remove(listener); super.removeOnAttachStateChangeListener(listener);
        }
        public int listenerCount() { return listeners.size(); }
    }

    public static final class Row {
        public final TextView view;
        public final ViewScope scope;
        public int bindCount;
        public int position;
        Row(TextView view, ViewScope scope) { this.view = view; this.scope = scope; }
    }

    public static final class RecyclingRows extends BaseAdapter {
        private final PlatformFixtureActivity activity;
        public final List<Row> rows = new ArrayList<>();
        public int generation;
        public int rebinds;
        public int recycles;
        public RecyclingRows(PlatformFixtureActivity activity) { this.activity = activity; }
        public void recycle(View view) {
            Row row = (Row) view.getTag(); row.scope.rebind(); recycles++;
        }
        @Override public int getCount() { return 48; }
        @Override public Object getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Row row;
            if (convertView == null) {
                TextView view = new TextView(activity); view.setMinHeight(activity.dp(48));
                row = new Row(view, activity.session.scope(view)); view.setTag(row); rows.add(row);
            } else {
                row = (Row) convertView.getTag(); row.scope.rebind(); rebinds++;
            }
            row.bindCount++; row.position = position;
            // Initial adapter binding is synchronous; async test results use a lease after attachment.
            row.view.setText("Row " + position + " generation " + generation);
            return row.view;
        }
    }
}
