package org.soynx.mcp.knowledgebase.tools;

import org.soynx.mcp.knowledgebase.service.VaultService;

import java.util.List;

/**
 * Package-private utility for handling path prefixing and routing in multi-repo setups.
 * <p>
 * When a setup contains more than one {@link VaultService}, all file paths in tool outputs
 * are prefixed with {@code repoKey/} (e.g. {@code vault/Projects/MyNote.md}) so the AI
 * can distinguish which repo a file belongs to and pass it back to tools that need to
 * read or route to a specific file.
 * </p>
 * <p>
 * Single-repo setups use no prefix — behaviour is identical to the pre-multi-repo version.
 * </p>
 */
final class MultiRepoPathUtil {

    private MultiRepoPathUtil() {}

    /**
     * Returns {@code true} if the setup has more than one repository (multi-repo mode).
     */
    static boolean isMultiRepo(List<VaultService> services) {
        return services.size() > 1;
    }

    /**
     * Prefixes a vault-relative path with {@code repoKey/} in multi-repo mode.
     * In single-repo mode, returns the path unchanged.
     *
     * @param vaultRelativePath path as returned by the GitHub API, e.g. {@code "Projects/Note.md"}
     * @param repoKey           the logical repo key, e.g. {@code "vault"}
     * @param multiRepo         whether multi-repo mode is active
     * @return prefixed path (multi-repo) or original path (single-repo)
     */
    static String prefixPath(String vaultRelativePath, String repoKey, boolean multiRepo) {
        if (!multiRepo) return vaultRelativePath;
        return repoKey + "/" + vaultRelativePath;
    }

    /**
     * Splits a path that may carry a {@code repoKey/} prefix into its two components.
     * <p>
     * In single-repo mode, returns the only service's key + the original path unchanged.
     * In multi-repo mode, the prefix before the first {@code /} is treated as the repo key
     * and the remainder as the vault-relative path.
     * </p>
     *
     * @param prefixedPath path as passed in by the AI, e.g. {@code "vault/Projects/Note.md"} or
     *                     {@code "Projects/Note.md"} (single-repo)
     * @param services     the services registered for this setup
     * @return two-element array {@code [repoKey, localPath]}; never {@code null}
     */
    static String[] splitPrefix(String prefixedPath, List<VaultService> services) {
        if (services.size() == 1) {
            return new String[]{ services.getFirst().getRepoKey(), prefixedPath };
        }
        int slash = prefixedPath.indexOf('/');
        if (slash < 0) {
            // Only a repo key was given, no sub-path
            return new String[]{ prefixedPath, "" };
        }
        return new String[]{ prefixedPath.substring(0, slash), prefixedPath.substring(slash + 1) };
    }

    /**
     * Finds the {@link VaultService} whose {@link VaultService#getRepoKey()} matches {@code repoKey}.
     *
     * @param services list of services for this setup
     * @param repoKey  the key to look up
     * @return the matching service, or {@code null} if not found
     */
    static VaultService findService(List<VaultService> services, String repoKey) {
        return services.stream()
                .filter(s -> s.getRepoKey().equals(repoKey))
                .findFirst()
                .orElse(null);
    }
}
