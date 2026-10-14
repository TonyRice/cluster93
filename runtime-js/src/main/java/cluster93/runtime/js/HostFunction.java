package cluster93.runtime.js;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A binding method scripts can call, under {@code name} on its host object (D-126). Engine adapters expose these: Javet
 * as V8 functions, GraalJS as proxy functions.
 *
 * <p>Parameters are {@link JsArg} for any JavaScript value, or {@code int}, {@code double}, {@code boolean} or
 * {@code String}. A method returns nothing, or a value the adapter converts: a JSON-compatible
 * Java value, {@code byte[]}, {@link Js#UNDEFINED}, a {@link JsError} or a {@link JsHandle}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HostFunction {
    String name();
}
