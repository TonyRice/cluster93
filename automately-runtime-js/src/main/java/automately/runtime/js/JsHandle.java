package automately.runtime.js;

/**
 * A value that already lives in the engine (a promise, an evaluated script, a module's namespace), passed back to it
 * unchanged. Its owner is the engine adapter that made it.
 */
public record JsHandle(Object value) {
}
