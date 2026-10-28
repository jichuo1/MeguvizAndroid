package io.github.jichuo1.meguviz.android.runtime.resources;

import java.util.Objects;

/** Immutable values only. It deliberately contains no Configuration or Context. */
public final class ResourceSnapshot {
    public final int densityDpi, layoutDirection, uiMode, widthDp, heightDp, displayId;
    public final float fontScale;
    public final String locales, configuration;

    public ResourceSnapshot(int densityDpi, float fontScale, String locales, int layoutDirection,
                            int uiMode, int widthDp, int heightDp, int displayId, String configuration) {
        this.densityDpi = densityDpi; this.fontScale = fontScale;
        this.locales = Objects.requireNonNull(locales); this.layoutDirection = layoutDirection;
        this.uiMode = uiMode; this.widthDp = widthDp; this.heightDp = heightDp;
        this.displayId = displayId; this.configuration = Objects.requireNonNull(configuration);
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ResourceSnapshot)) return false;
        ResourceSnapshot s = (ResourceSnapshot) other;
        return densityDpi == s.densityDpi && Float.compare(fontScale, s.fontScale) == 0
                && layoutDirection == s.layoutDirection && uiMode == s.uiMode
                && widthDp == s.widthDp && heightDp == s.heightDp && displayId == s.displayId
                && locales.equals(s.locales) && configuration.equals(s.configuration);
    }
    @Override public int hashCode() {
        return Objects.hash(densityDpi, fontScale, locales, layoutDirection, uiMode,
                widthDp, heightDp, displayId, configuration);
    }
}
