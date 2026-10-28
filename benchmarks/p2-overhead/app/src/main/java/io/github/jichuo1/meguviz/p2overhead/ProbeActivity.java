package io.github.jichuo1.meguviz.p2overhead;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class ProbeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Meguviz P2 paired overhead probe");
        setContentView(text);
    }
}
