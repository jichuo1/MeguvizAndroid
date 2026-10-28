package io.github.jichuo1.meguviz.samples.plain;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import io.github.jichuo1.meguviz.android.runtime.core.BindingLease;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState;
import io.github.jichuo1.meguviz.android.window.*;
import io.github.jichuo1.meguviz.android.lifecycle.LifecycleAdapters;

/** Actual XML, Fragment view, Dialog and rebound row consumers of the local Maven AARs. */
public class PlainActivity extends AppCompatActivity {
    private WindowSession session, dialogSession;
    private Dialog activeDialog;
    private ViewScope row;
    private int generation;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_plain);
        session = WindowSession.open(getWindow(), this, getPackageName(), WindowSession.Permission.OWN_WINDOW);
        row = session.scope(findViewById(R.id.status));
        findViewById(R.id.rebind).setOnClickListener(v -> {
            BindingLease old = row.binding(ResourceState.ALL);
            row.rebind(); generation++;
            row.commit(old, () -> { throw new AssertionError("Old binding entered"); });
            row.commit(row.binding(ResourceState.ALL), () -> ((TextView)row.view()).setText("Binding " + generation));
        });
        findViewById(R.id.dialog).setOnClickListener(v -> {
            if (activeDialog != null) activeDialog.dismiss();
            Dialog dialog = new Dialog(this);
            TextView text = new TextView(this); text.setText("Independent dialog lifecycle"); dialog.setContentView(text);
            if (dialogSession != null) dialogSession.close();
            WindowSession child = WindowSession.open(dialog.getWindow(), dialog.getContext(), getPackageName() + ":dialog", WindowSession.Permission.OWN_WINDOW);
            dialogSession = child; activeDialog = dialog; child.setStarted(true); child.scope(text);
            dialog.setOnDismissListener(ignored -> {
                child.close(); if (dialogSession == child) dialogSession = null;
                if (activeDialog == dialog) activeDialog = null;
            });
            dialog.show();
        });
        if (state == null) getSupportFragmentManager().beginTransaction().replace(R.id.fragment, new ContentFragment()).commit();
    }
    @Override protected void onStart() { super.onStart(); session.setStarted(true); }
    @Override protected void onStop() { session.setStarted(false); super.onStop(); }
    @Override protected void onDestroy() {
        if (activeDialog != null) activeDialog.dismiss();
        if (dialogSession != null) dialogSession.close();
        if (session != null) session.close(); super.onDestroy();
    }
    public static class ContentFragment extends Fragment {
        private ViewScope scope;
        @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
            TextView text = new TextView(requireContext()); text.setText("Fragment view owner"); return text;
        }
        @Override public void onViewCreated(View view, Bundle state) {
            scope = ((PlainActivity)requireActivity()).session.scope(view);
            LifecycleAdapters.bindFragmentView(this, scope);
        }
        @Override public void onDestroyView() { scope = null; super.onDestroyView(); }
    }
}
