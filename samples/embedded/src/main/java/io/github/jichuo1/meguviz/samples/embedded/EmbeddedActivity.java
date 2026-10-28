package io.github.jichuo1.meguviz.samples.embedded;

import android.app.Activity;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.jichuo1.meguviz.android.window.WindowSession;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceScope;

/** Separate target29 process. Host and module have explicit resource namespaces. */
public class EmbeddedActivity extends Activity {
    private WindowSession host;
    private ResourceScope module;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        TextView sibling = new TextView(this); sibling.setText("Host-owned sibling"); root.addView(sibling);
        ContextThemeWrapper local = new ContextThemeWrapper(this, android.R.style.Theme_Material_Light);
        TextView child = new TextView(local); child.setText("Local theme; target29; observe-only window"); root.addView(child);
        setContentView(root);
        host = WindowSession.open(getWindow(), this, "host", WindowSession.Permission.OBSERVE);
        module = host.scope(child, "module").resources();
    }
    @Override protected void onStart() { super.onStart(); host.setStarted(true); }
    @Override protected void onStop() { host.setStarted(false); super.onStop(); }
    @Override protected void onDestroy() { host.close(); module = null; super.onDestroy(); }
}
