package automately.worker.javet;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Native image only (D-126): Javet's class loader defines its native bindings from bytecode at run time, so its library
 * can be reloaded, which a native image can't. Here it hands back the classes compiled into the image instead. Javet's
 * own source is unchanged.
 */
@TargetClass(className = "com.caoccao.javet.interop.JavetClassLoader")
final class Target_JavetClassLoader {

    @Substitute
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }
}
