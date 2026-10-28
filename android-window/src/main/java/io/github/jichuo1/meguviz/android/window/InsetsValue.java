package io.github.jichuo1.meguviz.android.window;

import java.util.Objects;

/** Immutable physical edges in pixels. */
public final class InsetsValue {
    public static final InsetsValue ZERO = new InsetsValue(0, 0, 0, 0);
    public final int left, top, right, bottom;
    public InsetsValue(int left, int top, int right, int bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
    public InsetsValue plus(InsetsValue other) {
        return new InsetsValue(Math.addExact(left, other.left), Math.addExact(top, other.top),
                Math.addExact(right, other.right), Math.addExact(bottom, other.bottom));
    }
    public InsetsValue max(InsetsValue other) {
        return new InsetsValue(Math.max(left, other.left), Math.max(top, other.top),
                Math.max(right, other.right), Math.max(bottom, other.bottom));
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof InsetsValue)) return false;
        InsetsValue value = (InsetsValue) other;
        return left == value.left && top == value.top && right == value.right && bottom == value.bottom;
    }
    @Override public int hashCode() { return Objects.hash(left, top, right, bottom); }
}
