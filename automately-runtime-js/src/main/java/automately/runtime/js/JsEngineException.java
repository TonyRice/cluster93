package automately.runtime.js;

/**
 * The engine failed or was terminated: what Javet's {@code JavetException} and GraalJS's {@code PolyglotException}
 * become, so the engine-neutral code handles both one way (D-126).
 */
public final class JsEngineException extends Exception {

    public JsEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
