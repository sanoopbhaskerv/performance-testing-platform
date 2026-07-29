package com.perfplatform.api.v2.security;

import com.perfplatform.domain.security.CallerContext;

/**
 * Resolves who is making the current request.
 *
 * <p>An interface here, implemented by the composition root. The V2 API needs a caller identity to
 * authorize anything, but choosing an identity provider is a pause condition (CLAUDE.md §31) and
 * corporate OIDC is Increment 4. This seam lets the authorization rules be written and tested now
 * against a shape that will not change when the provider is decided — only the implementation moves.
 *
 * <p>An implementation must never fabricate an identity to make a request succeed. Returning a
 * caller with no teams is correct when the request is unauthenticated; the authorization check then
 * fails, which is the intended outcome.
 */
public interface CallerContextProvider {

    /**
     * The caller behind the request being handled.
     *
     * @throws UnauthenticatedException if no identity could be established
     */
    CallerContext current();

    /** Raised when the request carries no usable identity. */
    class UnauthenticatedException extends RuntimeException {
        public UnauthenticatedException(String message) {
            super(message);
        }
    }
}
