package automately.core.host;

import automately.core.runtime.api.HostApis;

/** Builds the host APIs for one execution; the node calls this when it starts an attempt. */
@FunctionalInterface
public interface HostApisFactory {

    HostApis create(String tenant, String jobId, boolean service);

    /** As above, for a job whose packages are loaded: {@code fs} shows their files at {@code /packages} (D-091). */
    default HostApis create(String tenant, String jobId, boolean service, automately.core.runtime.Packages packages) {
        return create(tenant, jobId, service);
    }

    HostApisFactory NONE = (tenant, jobId, service) -> HostApis.NONE;
}
