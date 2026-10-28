package io.github.jichuo1.meguviz.p2budgetpolicy;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class ProbeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Meguviz budget policy paired probe");
        setContentView(text);
    }
}
