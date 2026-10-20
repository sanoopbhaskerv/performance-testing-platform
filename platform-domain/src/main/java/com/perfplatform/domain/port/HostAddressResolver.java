package com.perfplatform.domain.port;

import java.net.InetAddress;
import java.util.List;

/**
 * Resolves a hostname to the addresses a recorder session will be frozen to.
 *
 * <p>A port rather than a direct {@code InetAddress.getAllByName} call because freezing DNS is the
 * anti-rebinding control (recorder security review §4.2): the addresses resolved here are the ones
 * pinned into the browser's resolver and into the generated NetworkPolicy, and a test needs to be
 * able to hand back a metadata address to prove the session is refused.
 */
public interface HostAddressResolver {

    /**
     * @return every address the host resolves to, never empty
     * @throws UnresolvableHostException if the name does not resolve
     */
    List<InetAddress> resolve(String host);

    class UnresolvableHostException extends RuntimeException {
        public UnresolvableHostException(String host, Throwable cause) {
            super("Recorder target host '" + host + "' does not resolve.", cause);
        }
    }
}
