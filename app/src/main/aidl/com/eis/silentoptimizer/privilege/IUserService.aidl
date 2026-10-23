// IUserService.aidl
package com.eis.silentoptimizer.privilege;

interface IUserService {
    /**
     * Executes one allowlisted command as the UserService process's identity
     * (ADB shell, or root when bound by the root runner) and returns stdout.
     * Empty string means the command was rejected by the allowlist or failed;
     * details are logged to logcat under the EIS-UserService tag.
     *
     * This is deliberately the only privileged operation exposed. It never
     * touches hidden framework APIs (IPackageManager/ServiceManager), which
     * are unavailable to SDK-compiled code.
     */
    String exec(String command);

    /** Identity the UserService process runs as, e.g. "uid=2000 pid=1234". */
    String getIdentity();
}
