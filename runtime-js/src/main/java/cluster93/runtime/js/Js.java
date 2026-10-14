package cluster93.runtime.js;

import cluster93.core.runtime.HostException;

/** Values and argument rules the bindings share across engines (D-126). */
public final class Js {

    /** Resolves a promise or answers a call with {@code undefined} rather than {@code null}. */
    public static final Object UNDEFINED = new Object() {
        @Override
        public String toString() {
            return "undefined";
        }
    };

    private Js() {
    }

    /** A strict integer: a small integer, a BigInt or a whole double within int's range; anything else is refused. */
    public static int integer(JsArg value) {
        Integer small = value.smallInteger();
        if (small != null) {
            return small;
        }
        Long big = value.bigint();
        if (big != null && big >= Integer.MIN_VALUE && big <= Integer.MAX_VALUE) {
            return big.intValue();
        }
        Double number = value.number();
        if (number != null && number == Math.rint(number) && Math.abs(number) <= Integer.MAX_VALUE) {
            return number.intValue();
        }
        throw HostException.invalid("Expected an integer");
    }

    /** True only for the boolean {@code true}. */
    public static boolean flag(JsArg value) {
        return Boolean.TRUE.equals(value.bool());
    }
}
