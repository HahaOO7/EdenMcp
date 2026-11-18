package at.haha007.edenMcp.core.resource;

/**
 * Produces the content of a resource for one read request.
 */
@FunctionalInterface
public interface ResourceReader {
    ResourceContents read(ResourceContext context) throws Exception;
}
