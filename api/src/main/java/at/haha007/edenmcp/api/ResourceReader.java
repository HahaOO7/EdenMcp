package at.haha007.edenmcp.api;

/** Produces the content of a resource for one read request. */
@FunctionalInterface
public interface ResourceReader {
    ResourceContents read(ResourceContext context) throws Exception;
}
