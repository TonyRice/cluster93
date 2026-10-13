package cluster93.runtime.js;

/** The job's compiled module, as one engine holds it. */
public interface JsModule extends AutoCloseable {

    @Override
    void close();
}
