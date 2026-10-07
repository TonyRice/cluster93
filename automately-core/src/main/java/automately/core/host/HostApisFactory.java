package automately.core.host;

import automately.core.runtime.api.HostApis;

/** Builds the host APIs for one execution; the node calls this when it starts an attempt. */
@FunctionalInterface
public interface HostApisFactory {

    HostApis create(String tenant, String jobId, boolean service);

    HostApisFactory NONE = (tenant, jobId, service) -> HostApis.NONE;
}
