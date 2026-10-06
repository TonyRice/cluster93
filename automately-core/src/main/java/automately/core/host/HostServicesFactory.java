package automately.core.host;

import automately.core.runtime.HostServices;

/** Builds the host services for one execution; the node calls this when it starts an attempt. */
@FunctionalInterface
public interface HostServicesFactory {

    HostServices create(String tenant, String jobId, boolean service);

    HostServicesFactory NONE = (tenant, jobId, service) -> HostServices.NONE;
}
