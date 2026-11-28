/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.mcp.server.collection;

import static org.apache.solr.mcp.server.collection.CollectionUtils.getFloat;
import static org.apache.solr.mcp.server.collection.CollectionUtils.getInteger;
import static org.apache.solr.mcp.server.collection.CollectionUtils.getLong;

import io.micrometer.observation.annotation.Observed;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.LukeRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.LukeResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.mcp.server.collection.CollectionMetrics.CacheInfo;
import org.apache.solr.mcp.server.collection.CollectionMetrics.CacheStats;
import org.apache.solr.mcp.server.collection.CollectionMetrics.HandlerInfo;
import org.apache.solr.mcp.server.collection.CollectionMetrics.HandlerStats;
import org.apache.solr.mcp.server.collection.CollectionMetrics.IndexStats;
import org.apache.solr.mcp.server.config.SolrConfigurationProperties;
import org.apache.solr.mcp.server.indexing.IndexingService;
import org.apache.solr.mcp.server.schema.SchemaService;
import org.apache.solr.mcp.server.search.SearchService;
import org.apache.solr.mcp.server.security.McpAuthorities;
import org.apache.solr.mcp.server.util.McpResults;
import org.apache.solr.mcp.server.util.PromptArguments;
import org.apache.solr.mcp.server.util.ResourceUris;
import org.apache.solr.mcp.server.util.SolrCalls;
import org.apache.solr.mcp.server.util.SolrQueries;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Service providing comprehensive Solr collection management and monitoring capabilities for
 * Model Context Protocol (MCP) clients.
 *
 * <p>This service acts as the primary interface for collection-level operations in the Solr MCP
 * Server, providing tools for collection discovery, metrics gathering, health monitoring, and
 * performance analysis. It bridges the gap between MCP clients (like Claude Desktop) and Apache
 * Solr through the SolrJ client library.
 *
 * <p><strong>Core Capabilities:</strong>
 *
 * <ul>
 *   <li><strong>Collection Discovery</strong>: Lists available collections using the SolrCloud
 *       Collections API
 *   <li><strong>Performance Monitoring</strong>: Comprehensive metrics collection including index,
 *       query, cache, and handler statistics
 *   <li><strong>Health Monitoring</strong>: Real-time health checks with availability and
 *       performance indicators
 *   <li><strong>Shard-Aware Operations</strong>: Intelligent handling of SolrCloud shard names and
 *       collection name extraction
 * </ul>
 *
 * <p><strong>Implementation Details:</strong>
 *
 * <p>This class uses extensively documented constants for all API parameters, field names, and
 * paths to ensure maintainability and reduce the risk of typos. All string literals have been
 * replaced with well-named constants that are organized by category (API parameters, response
 * parsing keys, handler paths, statistics fields, etc.).
 *
 * <p><strong>MCP Tool Integration:</strong>
 *
 * <p>Methods annotated with {@code @McpTool} are automatically exposed as MCP tools that can be
 * invoked by AI clients. These tools provide natural language interfaces to Solr operations.
 *
 * <p><strong>Supported Solr Deployments:</strong>
 *
 * <ul>
 *   <li><strong>SolrCloud</strong>: Distributed mode using Collections API
 * </ul>
 *
 * <p><strong>Error Handling:</strong>
 *
 * <p>Collection-level operations (listing, stats, creation) call Solr through {@link SolrCalls},
 * which translates a failure into an exception whose message the MCP client receives as an {@code
 * isError} result, Solr unreachable included. {@code check-health} reports a fixed {@link
 * HealthStatus.Reason} instead. Sub-metric operations (cache, handler) degrade gracefully and
 * return {@code null} when their specific endpoints are unavailable.
 *
 * <p><strong>Example Usage:</strong>
 *
 * <pre>{@code
 * // List all available collections
 * List<Collection> collections = collectionService.listCollections();
 *
 * // Get comprehensive metrics for a collection
 * CollectionMetrics metrics = collectionService.getCollectionStats("my_collection");
 *
 * // Check collection health
 * HealthStatus health = collectionService.checkHealth("my_collection");
 * }</pre>
 *
 * @see CollectionMetrics
 * @see HealthStatus
 * @see org.apache.solr.client.solrj.SolrClient
 */
@Service
@Observed
public class CollectionService {

  private static final Logger logger = LoggerFactory.getLogger(CollectionService.class);

  /** Name of the {@code explore-collections} prompt. */
  private static final String EXPLORE_COLLECTIONS_PROMPT = "explore-collections";

  /**
   * Description of the {@code explore-collections} prompt, shared by its {@code @McpPrompt} and its
   * result.
   */
  private static final String EXPLORE_COLLECTIONS_PROMPT_DESCRIPTION =
      "Read-only walkthrough: list collections and characterise each by stats and health.";

  /** Name of the {@code setup-collection} prompt. */
  private static final String SETUP_COLLECTION_PROMPT = "setup-collection";

  /**
   * Description of the {@code setup-collection} prompt, shared by its {@code @McpPrompt} and its
   * result.
   */
  private static final String SETUP_COLLECTION_PROMPT_DESCRIPTION =
      "Guided workflow: validate a name, pick configset / shards / replication factor, create the collection, and verify it.";

  static final String COLLECTIONS_RESOURCE_ERROR =
      "Failed to list Solr collections. Retry later; if it persists,"
          + " ask the operator to check Solr availability and the server logs.";

  // ========================================
  // Constants for API Parameters and Paths
  // ========================================

  /**
   * Matches a SolrCloud shard/replica suffix at the end of a core name, e.g. {@code _shard1} or
   * {@code _shard1_replica_n1}. Anchored so that collection names merely containing "_shard" are
   * left intact.
   */
  private static final java.util.regex.Pattern SHARD_SUFFIX_PATTERN =
      java.util.regex.Pattern.compile("_shard\\d+(_replica.*)?$");

  /** Request parameter name for specifying response writer type */
  private static final String WT_PARAM = "wt";

  /** JSON format specification for response writer type */
  private static final String JSON_FORMAT = "json";

  /** URL path for Solr Metrics admin endpoint */
  private static final String ADMIN_METRICS_PATH = "/admin/metrics";

  /** Request parameter name for specifying the metrics group */
  private static final String GROUP_PARAM = "group";

  /** Request parameter name for filtering metrics by key prefix */
  private static final String PREFIX_PARAM = "prefix";

  /** Metrics group for core-level metrics */
  private static final String CORE_GROUP = "core";

  /** Prefix for cache metrics in the Metrics API */
  private static final String CACHE_METRIC_PREFIX = "CACHE.searcher";

  /** Prefix for select handler metrics in the Metrics API */
  private static final String SELECT_HANDLER_METRIC_PREFIX = "QUERY./select";

  /** Prefix for update handler metrics in the Metrics API */
  private static final String UPDATE_HANDLER_METRIC_PREFIX = "UPDATE./update";

  /** Cache and handler prefixes, comma-separated so one request fetches all */
  private static final String STATS_METRIC_PREFIXES =
      String.join(
          ",", CACHE_METRIC_PREFIX, SELECT_HANDLER_METRIC_PREFIX, UPDATE_HANDLER_METRIC_PREFIX);

  /** Request parameter name for restricting the Luke handler's output sections */
  private static final String SHOW_PARAM = "show";

  /** Luke {@value #SHOW_PARAM} value restricting output to the index section */
  private static final String SHOW_INDEX_VALUE = "index";

  // ========================================
  // Constants for Response Parsing
  // ========================================

  /** Key name for collections list in Collections API responses */
  private static final String COLLECTIONS_KEY = "collections";

  /** Key name for segment count information in Luke response */
  private static final String SEGMENT_COUNT_KEY = "segmentCount";

  /** Top-level key in Metrics API responses */
  private static final String METRICS_KEY = "metrics";

  /** Metrics API key for query result cache */
  private static final String QUERY_RESULT_CACHE_KEY = "CACHE.searcher.queryResultCache";

  /** Metrics API key for document cache */
  private static final String DOCUMENT_CACHE_KEY = "CACHE.searcher.documentCache";

  /** Metrics API key for filter cache */
  private static final String FILTER_CACHE_KEY = "CACHE.searcher.filterCache";

  /** Flat metric key prefix for select handler stats */
  private static final String SELECT_HANDLER_KEY = "QUERY./select.";

  /** Flat metric key prefix for update handler stats */
  private static final String UPDATE_HANDLER_KEY = "UPDATE./update.";

  // ========================================
  // Constants for Statistics Field Names
  // ========================================

  /** Field name for cache/handler lookup count statistics */
  private static final String LOOKUPS_FIELD = "lookups";

  /** Field name for cache hit count statistics */
  private static final String HITS_FIELD = "hits";

  /** Field name for cache hit ratio statistics */
  private static final String HITRATIO_FIELD = "hitratio";

  /** Field name for cache insert count statistics */
  private static final String INSERTS_FIELD = "inserts";

  /** Field name for cache eviction count statistics */
  private static final String EVICTIONS_FIELD = "evictions";

  /** Field name for cache size statistics */
  private static final String SIZE_FIELD = "size";

  /** Field name for handler request count statistics */
  private static final String REQUESTS_FIELD = "requests";

  /** Field name for handler error count statistics */
  private static final String ERRORS_FIELD = "errors";

  /** Field name for handler timeout count statistics */
  private static final String TIMEOUTS_FIELD = "timeouts";

  /** Field name for handler total processing time statistics */
  private static final String TOTAL_TIME_FIELD = "totalTime";

  // ========================================
  // Constants for Error Messages
  // ========================================

  /** Leads the error text of a failed {@code list-collections}. */
  static final String LIST_COLLECTIONS_FAILED = "Listing collections failed";

  /** Leads the error text of a failed {@code get-collection-stats}. */
  static final String COLLECTION_STATS_FAILED = "Getting collection stats failed";

  /** Leads the error text of a failed {@code create-collection}. */
  static final String CREATE_COLLECTION_FAILED = "Creating the collection failed";

  /** Default configset name used when none is specified */
  private static final String DEFAULT_CONFIGSET = "_default";

  /**
   * Suffix Solr gives the private copy of {@value #DEFAULT_CONFIGSET} it makes for a collection
   * created without a configset. Solr's own constant, {@code
   * ConfigSetsHandler.AUTOCREATED_CONFIGSET_SUFFIX}, is in solr-core, which is not on the
   * classpath.
   */
  private static final String AUTOCREATED_SUFFIX = ".AUTOCREATED";

  /** Default number of shards for new collections */
  private static final int DEFAULT_NUM_SHARDS = 1;

  /** Default replication factor for new collections */
  private static final int DEFAULT_REPLICATION_FACTOR = 1;

  /** Error message for blank collection name validation */
  private static final String BLANK_COLLECTION_NAME_ERROR = "Collection name must not be blank";

  /** SolrJ client for communicating with Solr server */
  private final SolrClient solrClient;

  private final JsonMapper jsonMapper;

  /**
   * Constructs a new CollectionService with the required dependencies.
   *
   * <p>This constructor is automatically called by Spring's dependency injection framework during
   * application startup.
   *
   * @param solrClient the SolrJ client instance for communicating with Solr
   * @param jsonMapper the Jackson JsonMapper for JSON serialization
   * @see SolrClient
   * @see SolrConfigurationProperties
   */
  public CollectionService(SolrClient solrClient, JsonMapper jsonMapper) {
    this.solrClient = solrClient;
    this.jsonMapper = jsonMapper;
  }

  /**
   * A {@link LukeRequest} for the {@code index} section only, which is all {@link
   * #buildIndexStats(LukeResponse)} reads. Without {@code show=index} Solr also lists every field;
   * SolrJ has no setter for it.
   */
  private static final class IndexOnlyLukeRequest extends LukeRequest {
    @Override
    public SolrParams getParams() {
      ModifiableSolrParams params = new ModifiableSolrParams(super.getParams());
      params.set(SHOW_PARAM, SHOW_INDEX_VALUE);
      return params;
    }
  }

  /**
   * MCP Resource endpoint that returns a list of all available Solr collections.
   *
   * <p>This resource provides a simple way for MCP clients to discover what collections are
   * available in the Solr cluster. The returned JSON contains an array of collection names.
   *
   * @param request the read request, whose URI the result echoes
   * @return the collection list as one {@code application/json} resource content
   * @throws IllegalStateException with {@link #COLLECTIONS_RESOURCE_ERROR} if the collections
   *     cannot be listed or serialized
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpResource(
      uri = ResourceUris.COLLECTIONS,
      name = "solr-collections",
      description = "List of all Solr collections available in the cluster",
      mimeType = MimeTypeUtils.APPLICATION_JSON_VALUE)
  public ReadResourceResult getCollectionsResource(ReadResourceRequest request) {
    try {
      return McpResults.jsonResource(
          request.uri(), jsonMapper.writeValueAsString(listCollections()));
    } catch (IllegalArgumentException | IllegalStateException _) {
      // SolrCalls' translation of a failed listing; SolrCalls has already logged the cause.
      throw new IllegalStateException(COLLECTIONS_RESOURCE_ERROR);
    } catch (Exception e) {
      // Deliberately no cause: MCP reports the root cause's message to the client,
      // and Solr and SolrJ failure text can carry internal host names, URLs and
      // paths. The log is the sole record of the cause.
      logger.error("Failed to list collections for the collections resource", e);
      throw new IllegalStateException(COLLECTIONS_RESOURCE_ERROR);
    }
  }

  /** Maximum number of completion suggestions returned per request. */
  static final int MAX_COMPLETION_RESULTS = 100;

  /**
   * MCP Completion endpoint for the {@code {collection}} segment of the {@code
   * solr://{collection}/schema} resource template.
   *
   * <p>Returns collection names that start with the user-supplied prefix (case-insensitive). When
   * the prefix is empty all collections are returned, subject to {@link #MAX_COMPLETION_RESULTS}.
   * The results are sorted so that client UIs see a stable ordering.
   *
   * @param argument the partial value the client is completing; its {@link
   *     CompleteRequest.CompleteArgument#name() name} must be {@code collection}
   * @return up to {@link #MAX_COMPLETION_RESULTS} matching names, with the total match count
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpComplete(uri = ResourceUris.COLLECTION_SCHEMA)
  public CompleteResult completeCollection(CompleteRequest.CompleteArgument argument) {
    if (!PromptArguments.COLLECTION.equals(argument.name())) {
      return McpResults.completion(List.of(), MAX_COMPLETION_RESULTS);
    }
    String prefix = argument.value().toLowerCase(Locale.ROOT);
    try {
      List<String> matches =
          collections(Level.DEBUG).stream()
              .map(Collection::name)
              .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
              .sorted()
              .toList();
      return McpResults.completion(matches, MAX_COMPLETION_RESULTS);
    } catch (IllegalArgumentException | IllegalStateException _) {
      // SolrCalls' translations of a failed listing, logged at DEBUG: a completion runs on every
      // keystroke, so it offers nothing rather than an error.
      return McpResults.completion(List.of(), MAX_COMPLETION_RESULTS);
    }
  }

  /**
   * Completion for the {@code {collection}} segment of the {@code solr://{collection}/fields}
   * resource template (defined in {@code SchemaService}).
   *
   * @param argument the partial value the client is completing
   * @return up to {@link #MAX_COMPLETION_RESULTS} matching names, with the total match count
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpComplete(uri = ResourceUris.COLLECTION_FIELDS)
  public CompleteResult completeFieldsResourceCollection(
      CompleteRequest.CompleteArgument argument) {
    return completeCollection(argument);
  }

  /**
   * Completion for the {@code collection} argument of the {@code search-collection} prompt (defined
   * in {@code SearchService}).
   *
   * <p>{@code @McpComplete} registers a handler per {@code (ref/prompt, name)} pair, so a prompt
   * that takes a collection argument needs its own handler — the resource-template handler on
   * {@link #completeCollection} only matches {@code ref/resource}. Each wrapper delegates so all
   * collection-name completion shares one implementation and one cap.
   *
   * @param argument the partial value the client is completing
   * @return up to {@link #MAX_COMPLETION_RESULTS} matching names, with the total match count
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpComplete(prompt = SearchService.SEARCH_COLLECTION_PROMPT)
  public CompleteResult completeSearchCollectionPromptArg(
      CompleteRequest.CompleteArgument argument) {
    return completeCollection(argument);
  }

  /**
   * Completion for the {@code collection} argument of the {@code index-data} prompt.
   *
   * @param argument the partial value the client is completing
   * @return up to {@link #MAX_COMPLETION_RESULTS} matching names, with the total match count
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpComplete(prompt = IndexingService.INDEX_DATA_PROMPT)
  public CompleteResult completeIndexDataPromptArg(CompleteRequest.CompleteArgument argument) {
    return completeCollection(argument);
  }

  /**
   * Completion for the {@code collection} argument of the {@code view-schema} prompt.
   *
   * @param argument the partial value the client is completing
   * @return up to {@link #MAX_COMPLETION_RESULTS} matching names, with the total match count
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpComplete(prompt = SchemaService.VIEW_SCHEMA_PROMPT)
  public CompleteResult completeViewSchemaPromptArg(CompleteRequest.CompleteArgument argument) {
    return completeCollection(argument);
  }

  /**
   * Completion for the {@code collection} argument of the {@code design-schema} prompt.
   *
   * @param argument the partial value the client is completing
   * @return up to {@link #MAX_COMPLETION_RESULTS} matching names, with the total match count
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpComplete(prompt = SchemaService.DESIGN_SCHEMA_PROMPT)
  public CompleteResult completeDesignSchemaPromptArg(CompleteRequest.CompleteArgument argument) {
    return completeCollection(argument);
  }

  /**
   * Lists all available Solr collections in the SolrCloud cluster.
   *
   * <p>This method uses the SolrCloud Collections API to retrieve the list of available
   * collections. Standalone Solr instances are not supported.
   *
   * <p>The returned names may include shard identifiers (e.g., "films_shard1_replica_n1"). Use
   * {@link #extractCollectionName(String)} to get the base collection name if needed.
   *
   * <p><strong>MCP Tool Usage:</strong>
   *
   * <p>This method is exposed as an MCP tool and can be invoked by AI clients with natural language
   * requests like "list all collections" or "show me available databases".
   *
   * @return the collections, one {@link Collection} per name; empty when Solr reports no
   *     collections)
   * @throws IllegalStateException if Solr failed or could not be reached, as {@link SolrCalls}
   *     reports it
   * @see CollectionAdminRequest.List
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpTool(
      name = "list-collections",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true),
      description = "List solr collections")
  public List<Collection> listCollections() {
    return collections(Level.WARN);
  }

  /**
   * Reads every collection name from Solr.
   *
   * @param logLevel the level a failure is logged at
   * @return the collections, one {@link Collection} per name
   */
  private List<Collection> collections(Level logLevel) {
    CollectionAdminResponse response =
        SolrCalls.call(
            LIST_COLLECTIONS_FAILED,
            SolrCalls.ADMIN_API_NOT_FOUND,
            logLevel,
            () -> new CollectionAdminRequest.List().process(solrClient));

    @SuppressWarnings("unchecked")
    List<String> collections = (List<String>) response.getResponse().get(COLLECTIONS_KEY);
    return collections != null ? collections.stream().map(Collection::new).toList() : List.of();
  }

  /**
   * Retrieves comprehensive performance metrics and statistics for a specified Solr collection.
   *
   * <p>This method aggregates metrics from multiple Solr endpoints to provide a complete
   * performance profile including index health, query performance, cache utilization, and request
   * handler statistics.
   *
   * <p><strong>Collected Metrics:</strong>
   *
   * <ul>
   *   <li><strong>Index Statistics</strong>: Document counts, segment information (via Luke
   *       handler)
   *   <li><strong>Query Performance</strong>: Response times, result counts, relevance scores
   *   <li><strong>Cache Utilization</strong>: Hit ratios, eviction rates for all cache types
   *   <li><strong>Handler Performance</strong>: Request volumes, error rates, throughput metrics
   * </ul>
   *
   * <p><strong>Collection Name Handling:</strong>
   *
   * <p>Supports both collection names and shard names. If a shard name like
   * "films_shard1_replica_n1" is provided, it will be automatically converted to the base
   * collection name "films" for API calls.
   *
   * <p><strong>Validation:</strong>
   *
   * <p>Collection existence is not checked with a separate pre-flight call: the Luke request below
   * already 404s for an unknown collection, and {@link SolrCalls} translates that response into an
   * {@code IllegalArgumentException} that names the collection and the {@code list-collections}
   * tool.
   *
   * <p><strong>MCP Tool Usage:</strong>
   *
   * <p>Exposed as an MCP tool for natural language queries like "get metrics for my_collection" or
   * "show me performance stats for the search index".
   *
   * @param collection the name of the collection to analyze (supports both collection and shard
   *     names)
   * @return comprehensive metrics object containing all collected statistics
   * @throws IllegalArgumentException if the specified collection does not exist, or Solr refused
   *     the request
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see CollectionMetrics
   * @see LukeRequest
   * @see #extractCollectionName(String)
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpTool(
      name = "get-collection-stats",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true),
      description =
          "Get index size and performance metrics for a Solr collection: document and segment"
              + " counts from the index, and cache and request handler statistics. It does not"
              + " describe fields; use list-fields for the fields that hold data. Before Solr 9.11"
              + " and 10.1, numDocs and segmentCount of a collection with more than one shard are"
              + " one shard's. caches and handlers are those of the one core named in"
              + " metricsCore, not the whole collection, and are omitted when Solr's"
              + " /admin/metrics API does not provide them; this is expected and not an error. A"
              + " cache's hitRatio is 1.0 when it has had no lookups. handlers times (totalTime,"
              + " avgTimePerRequest) are in nanoseconds.",
      generateOutputSchema = true)
  public CollectionMetrics getCollectionStats(
      @McpToolParam(description = "Solr collection to get stats/metrics for") String collection) {
    // Extract actual collection name from shard name if needed
    String actualCollection = extractCollectionName(collection);
    String notFound = SolrCalls.collectionNotFound(actualCollection);

    // Index statistics using Luke; an unknown collection 404s here
    LukeResponse lukeResponse =
        SolrCalls.call(
            COLLECTION_STATS_FAILED,
            notFound,
            () -> new IndexOnlyLukeRequest().process(solrClient, actualCollection));

    // One /admin/metrics call feeds both the cache and handler stats. No query is sent: the
    // handler and cache counters are cumulative, so a probe query would show up in this and every
    // later call's numbers.
    CoreMetrics coreMetrics = fetchMetrics(actualCollection);
    return new CollectionMetrics(
        buildIndexStats(lukeResponse),
        cacheStats(metricsOf(coreMetrics)),
        handlerStats(metricsOf(coreMetrics)),
        coreMetrics == null ? null : coreMetrics.core());
  }

  /**
   * Builds an IndexStats object from a Solr Luke response containing index metadata.
   *
   * <p>The Luke handler provides low-level Lucene index information including document counts,
   * segment details, and field statistics. This method extracts the essential index health metrics
   * for monitoring and analysis.
   *
   * <p><strong>Extracted Metrics:</strong>
   *
   * <ul>
   *   <li><strong>numDocs</strong>: Total number of documents excluding deleted ones
   *   <li><strong>segmentCount</strong>: Number of Lucene segments (performance indicator)
   * </ul>
   *
   * <p><strong>Performance Implications:</strong>
   *
   * <p>High segment counts may indicate the need for index optimization to improve search
   * performance. The optimal segment count depends on index size and update frequency.
   *
   * @param lukeResponse the Luke response containing raw index information
   * @return IndexStats object with extracted and formatted metrics
   * @see IndexStats
   * @see LukeResponse
   */
  IndexStats buildIndexStats(LukeResponse lukeResponse) {
    NamedList<Object> indexInfo = lukeResponse.getIndexInfo();

    // Extract index information using helper methods
    Integer segmentCount = getInteger(indexInfo, SEGMENT_COUNT_KEY);

    return new IndexStats(lukeResponse.getNumDocs(), segmentCount);
  }

  /**
   * Retrieves cache performance metrics for all cache types in a Solr collection.
   *
   * <p>Collects detailed cache utilization statistics from Solr's Metrics API, providing insights
   * into cache effectiveness and memory usage patterns. Cache performance directly impacts query
   * response times and system efficiency.
   *
   * <p><strong>Monitored Cache Types:</strong>
   *
   * <ul>
   *   <li><strong>Query Result Cache</strong>: Caches complete query results for identical searches
   *   <li><strong>Document Cache</strong>: Caches retrieved document field data
   *   <li><strong>Filter Cache</strong>: Caches filter query results for faceting and filtering
   * </ul>
   *
   * <p><strong>Key Performance Indicators:</strong>
   *
   * <ul>
   *   <li><strong>Hit Ratio</strong>: Cache effectiveness (higher is better)
   *   <li><strong>Evictions</strong>: Memory pressure indicator
   *   <li><strong>Size</strong>: Current cache utilization
   * </ul>
   *
   * <p><strong>Error Handling:</strong>
   *
   * <p>Returns {@code null} if cache statistics cannot be retrieved or if all cache types are
   * empty/unavailable. This allows graceful degradation when cache monitoring is not available.
   *
   * @param collection the collection name to retrieve cache metrics for
   * @return CacheStats object with all cache performance metrics, or null if unavailable
   * @see CacheStats
   * @see CacheInfo
   * @see #extractCacheStats(NamedList)
   * @see #isCacheStatsEmpty(CacheStats)
   */
  @Nullable CacheStats getCacheMetrics(String collection) {
    // An unknown collection has no core in the metrics response, so this is null
    return cacheStats(metricsOf(fetchMetrics(extractCollectionName(collection))));
  }

  /** Extracts {@link CacheStats} from fetched core metrics, or {@code null} if there are none. */
  private @Nullable CacheStats cacheStats(@Nullable NamedList<Object> coreMetrics) {
    if (coreMetrics == null) {
      return null;
    }
    CacheStats stats = extractCacheStats(coreMetrics);
    return isCacheStatsEmpty(stats) ? null : stats;
  }

  /**
   * Checks if cache statistics are empty or contain no meaningful data.
   *
   * <p>Used to determine whether cache metrics are worth returning to clients. Empty cache stats
   * typically indicate that caches are not configured or not yet populated with data.
   *
   * @param stats the cache statistics to evaluate
   * @return true if the stats are null or all cache types are null
   */
  private boolean isCacheStatsEmpty(@Nullable CacheStats stats) {
    return stats == null
        || (stats.queryResultCache() == null
            && stats.documentCache() == null
            && stats.filterCache() == null);
  }

  /**
   * Extracts cache performance statistics from Solr Metrics API response data.
   *
   * @param coreMetrics the core metrics from the Solr Metrics API
   * @return CacheStats object containing parsed metrics for all cache types
   */
  private CacheStats extractCacheStats(NamedList<Object> coreMetrics) {
    return new CacheStats(
        extractSingleCacheInfo(coreMetrics, QUERY_RESULT_CACHE_KEY),
        extractSingleCacheInfo(coreMetrics, DOCUMENT_CACHE_KEY),
        extractSingleCacheInfo(coreMetrics, FILTER_CACHE_KEY));
  }

  @SuppressWarnings("unchecked")
  private @Nullable CacheInfo extractSingleCacheInfo(NamedList<?> coreMetrics, String key) {
    NamedList<Object> cache = (NamedList<Object>) coreMetrics.get(key);
    if (cache == null) {
      return null;
    }
    return new CacheInfo(
        getLong(cache, LOOKUPS_FIELD),
        getLong(cache, HITS_FIELD),
        getFloat(cache, HITRATIO_FIELD),
        getLong(cache, INSERTS_FIELD),
        getLong(cache, EVICTIONS_FIELD),
        getLong(cache, SIZE_FIELD));
  }

  /**
   * Retrieves request handler performance metrics for core Solr operations.
   *
   * <p>Collects detailed performance statistics for the primary request handlers that process
   * search and update operations. Handler metrics provide insights into system throughput, error
   * rates, and response time characteristics.
   *
   * <p><strong>Monitored Handlers:</strong>
   *
   * <ul>
   *   <li><strong>Select Handler (/select)</strong>: Processes search and query requests
   *   <li><strong>Update Handler (/update)</strong>: Processes document indexing operations
   * </ul>
   *
   * <p><strong>Performance Metrics:</strong>
   *
   * <ul>
   *   <li><strong>Request Volume</strong>: Total requests processed
   *   <li><strong>Error Rates</strong>: Failed request counts and timeouts
   *   <li><strong>Performance</strong>: Average response times and throughput
   * </ul>
   *
   * <p><strong>Error Handling:</strong>
   *
   * <p>Returns {@code null} if handler statistics cannot be retrieved or if no meaningful handler
   * data is available. This allows graceful degradation when handler monitoring endpoints are not
   * accessible.
   *
   * @param collection the collection name to retrieve handler metrics for
   * @return HandlerStats object with performance metrics for all handlers, or null if unavailable
   * @see HandlerStats
   * @see HandlerInfo
   * @see #extractFlatHandlerInfo(NamedList, String)
   * @see #isHandlerStatsEmpty(HandlerStats)
   */
  @Nullable HandlerStats getHandlerMetrics(String collection) {
    // An unknown collection has no core in the metrics response, so this is null
    return handlerStats(metricsOf(fetchMetrics(extractCollectionName(collection))));
  }

  /**
   * Extracts {@link HandlerStats} from fetched core metrics, or {@code null} if there are none.
   * Handler metrics are flat keys (e.g. {@code QUERY./select.requests}), reassembled into a {@link
   * HandlerInfo} per handler.
   */
  private @Nullable HandlerStats handlerStats(@Nullable NamedList<Object> coreMetrics) {
    if (coreMetrics == null) {
      return null;
    }
    HandlerInfo selectHandler = extractFlatHandlerInfo(coreMetrics, SELECT_HANDLER_KEY);
    HandlerInfo updateHandler = extractFlatHandlerInfo(coreMetrics, UPDATE_HANDLER_KEY);
    HandlerStats stats = new HandlerStats(selectHandler, updateHandler);
    return isHandlerStatsEmpty(stats) ? null : stats;
  }

  /**
   * Checks if handler statistics are empty or contain no meaningful data.
   *
   * <p>Used to determine whether handler metrics are worth returning to clients. Empty handler
   * stats typically indicate that handlers haven't processed any requests yet or statistics
   * collection is not enabled.
   *
   * @param stats the handler statistics to evaluate
   * @return true if all handler types are null
   */
  private boolean isHandlerStatsEmpty(HandlerStats stats) {
    return stats.selectHandler() == null && stats.updateHandler() == null;
  }

  /**
   * Fetches the cache and handler metrics of a collection's core from the Solr Metrics API in one
   * request.
   *
   * @param collection the collection name
   * @return the first matching core's registry name and metrics, or null if unavailable
   */
  @SuppressWarnings("unchecked")
  private @Nullable CoreMetrics fetchMetrics(String collection) {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(GROUP_PARAM, CORE_GROUP);
    params.set(PREFIX_PARAM, STATS_METRIC_PREFIXES);
    params.set(WT_PARAM, JSON_FORMAT);

    // Metrics API is a node-level endpoint, not per-collection
    GenericSolrRequest request =
        new GenericSolrRequest(SolrRequest.METHOD.GET, ADMIN_METRICS_PATH, params);

    NamedList<?> response;
    try {
      response = solrClient.request(request);
    } catch (SolrServerException | IOException | SolrException e) {
      logger.debug("Cache/handler metrics unavailable for collection: {}", collection, e);
      return null;
    }
    NamedList<?> metrics = (NamedList<?>) response.get(METRICS_KEY);
    if (metrics == null || metrics.size() == 0) {
      return null;
    }

    // Find the core registry matching the requested collection
    // Keys are like "solr.core.<collection>.<shard>.<replica>"
    String corePrefix = "solr.core." + collection + ".";
    for (int i = 0; i < metrics.size(); i++) {
      String key = metrics.getName(i);
      if (key != null && key.startsWith(corePrefix)) {
        return new CoreMetrics(key, (NamedList<Object>) metrics.getVal(i));
      }
    }
    return null;
  }

  /**
   * One core's metrics, keyed in the Metrics API by its registry name.
   *
   * @param core the registry name, such as {@code solr.core.films.shard1.replica_n1}
   * @param metrics the core's metrics
   */
  private record CoreMetrics(String core, NamedList<Object> metrics) {}

  private static @Nullable NamedList<Object> metricsOf(@Nullable CoreMetrics coreMetrics) {
    return coreMetrics == null ? null : coreMetrics.metrics();
  }

  /**
   * Extracts a {@link HandlerInfo} from flat metric keys in core metrics.
   *
   * @param coreMetrics the core metrics NamedList with flat keys
   * @param keyPrefix the flat key prefix including trailing dot (e.g. {@code QUERY./select.})
   * @return HandlerInfo reconstructed from flat keys, or null if no requests key found
   */
  private @Nullable HandlerInfo extractFlatHandlerInfo(
      NamedList<Object> coreMetrics, String keyPrefix) {
    Long requests = getLong(coreMetrics, keyPrefix + REQUESTS_FIELD);
    if (requests == null) {
      return null;
    }
    Long errors = getLong(coreMetrics, keyPrefix + ERRORS_FIELD);
    Long timeouts = getLong(coreMetrics, keyPrefix + TIMEOUTS_FIELD);
    Long totalTime = getLong(coreMetrics, keyPrefix + TOTAL_TIME_FIELD);
    // Solr has no flat metric for the average time per request or for requests per
    // second, so the average time is derived from the total time and the request count.
    Float avgTimePerRequest =
        (requests > 0 && totalTime != null) ? (float) totalTime / requests : null;
    return new HandlerInfo(requests, errors, timeouts, totalTime, avgTimePerRequest);
  }

  /**
   * Extracts the actual collection name from a shard name in SolrCloud environments.
   *
   * <p>In SolrCloud deployments, collection operations often return shard names that include
   * replica and shard identifiers (e.g., "films_shard1_replica_n1"). This method extracts the base
   * collection name ("films") for use in API calls that require the collection name.
   *
   * <p><strong>Extraction Logic:</strong>
   *
   * <ul>
   *   <li>Detects a trailing {@code _shard<N>} or {@code _shard<N>_replica...} suffix
   *   <li>Returns the substring before the shard identifier
   *   <li>Returns the original string if no shard pattern is detected
   * </ul>
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>"films_shard1_replica_n1" → "films"
   *   <li>"products_shard2_replica_n3" → "products"
   *   <li>"simple_collection" → "simple_collection" (unchanged)
   * </ul>
   *
   * @param collectionOrShard the collection or shard name to parse
   * @return the extracted collection name, or the original string if no shard pattern found
   */
  String extractCollectionName(String collectionOrShard) {
    // Strip only a real SolrCloud shard/replica suffix. Matching a bare
    // "_shard" anywhere would truncate legitimate collection names such as
    // "orders_shard_archive" down to "orders".
    return SHARD_SUFFIX_PATTERN.matcher(collectionOrShard).replaceFirst("");
  }

  /**
   * Performs a comprehensive health check on a Solr collection.
   *
   * <p>Evaluates collection availability and performance by executing a ping operation and basic
   * query to gather health indicators. This method provides a quick way to determine if a
   * collection is operational and responding to requests.
   *
   * <p><strong>Health Check Components:</strong>
   *
   * <ul>
   *   <li><strong>Availability</strong>: Collection responds to ping requests
   *   <li><strong>Performance</strong>: Response time measurement
   *   <li><strong>Content</strong>: Document count verification using universal query ({@value
   *       SolrQueries#MATCH_ALL})
   * </ul>
   *
   * <p><strong>Success Criteria:</strong>
   *
   * <p>A collection is considered healthy if both the ping operation and a basic query complete
   * successfully without exceptions. Performance metrics are collected during the health check
   * process.
   *
   * <p><strong>Failure Handling:</strong>
   *
   * <p>If the health check fails, the result has {@code healthy=false} and a {@link
   * HealthStatus.Reason} saying which step failed. The exception itself is logged and kept out of
   * the result, because Solr's error text can quote host names, URLs and credentials.
   *
   * <p><strong>MCP Tool Usage:</strong>
   *
   * <p>Exposed as an MCP tool for natural language health queries like "check if my_collection is
   * healthy" or "is the search index working properly".
   *
   * @param collection the name of the collection to health check
   * @return HealthStatus object containing health assessment results
   * @see HealthStatus
   * @see SolrPingResponse
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpTool(
      name = "check-health",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true),
      generateOutputSchema = true,
      description =
          "Check that one collection answers queries: a ping plus its document count. It does not"
              + " report replica state, so it can report healthy while a replica is down; use"
              + " get-cluster-status for replicas and nodes.")
  public HealthStatus checkHealth(
      @McpToolParam(description = "Solr collection") String collection) {
    String actualCollection = extractCollectionName(collection);
    SolrPingResponse pingResponse;
    try {
      pingResponse = solrClient.ping(actualCollection);
    } catch (SolrException e) {
      logger.warn("Health check ping failed for collection: {}", collection, e);
      return HealthStatus.unhealthy(
          actualCollection,
          e.code() == SolrException.ErrorCode.NOT_FOUND.code
              ? HealthStatus.Reason.COLLECTION_NOT_FOUND
              : HealthStatus.Reason.PING_FAILED);
    } catch (SolrServerException | IOException e) {
      logger.warn("Health check could not reach Solr for collection: {}", collection, e);
      return HealthStatus.unhealthy(actualCollection, HealthStatus.Reason.SOLR_UNREACHABLE);
    }
    try {
      QueryResponse statsResponse =
          solrClient.query(actualCollection, new SolrQuery(SolrQueries.MATCH_ALL).setRows(0));
      return HealthStatus.healthy(
          actualCollection,
          pingResponse.getElapsedTime(),
          statsResponse.getResults().getNumFound());
    } catch (SolrException | SolrServerException | IOException e) {
      logger.warn("Health check query failed for collection: {}", collection, e);
      return HealthStatus.unhealthy(actualCollection, HealthStatus.Reason.QUERY_FAILED);
    }
  }

  /**
   * Creates a new Solr collection in the SolrCloud cluster.
   *
   * <p>Uses the SolrCloud Collections API. Standalone Solr instances are not supported.
   *
   * <p>Optional parameters default to sensible values when not provided by the MCP client:
   * numShards and replicationFactor both default to 1.
   *
   * <p>Without a configSet, or with {@value #DEFAULT_CONFIGSET}, the request names no configset,
   * and Solr copies {@value #DEFAULT_CONFIGSET} into a private {@code <name>.AUTOCREATED} configset
   * that it deletes with the collection. Binding to {@value #DEFAULT_CONFIGSET} by reference would
   * write one collection's schemaless field guesses into every later collection's schema. Any other
   * configSet is bound by reference, so its schema is shared.
   *
   * @param name the name of the collection to create (must not be blank)
   * @param configSet a configset to share (optional; omitted or {@value #DEFAULT_CONFIGSET} gives a
   *     private copy)
   * @param numShards number of shards (optional, defaults to 1)
   * @param replicationFactor replication factor (optional, defaults to 1)
   * @return the settings the collection was created with, including applied defaults
   * @throws IllegalArgumentException if the collection name is blank, or Solr refused the request,
   *     for example for a configset that does not exist; the message carries Solr's reason
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see SolrCalls
   */
  @PreAuthorize(McpAuthorities.ADMIN)
  @McpTool(
      name = "create-collection",
      annotations = @McpTool.McpAnnotations(destructiveHint = false),
      description =
          "Create a new Solr collection. By default it gets its own copy of the _default configset,"
              + " so its schema is independent of other collections. Pass configSet only to share an existing"
              + " configset; call list-configsets to see the available configsets. numShards and"
              + " replicationFactor default to 1.",
      generateOutputSchema = true)
  public CollectionCreationResult createCollection(
      @McpToolParam(description = "Name of the collection to create") String name,
      @McpToolParam(
              description =
                  "Omit, or pass _default, to get a private copy named <name>.AUTOCREATED that is"
                      + " deleted with the collection. Any other name shares that configset: schema changes"
                      + " made with add-fields apply to every collection that uses it.",
              required = false)
          @Nullable String configSet,
      @McpToolParam(description = "Number of shards. Defaults to 1.", required = false)
          @Nullable Integer numShards,
      @McpToolParam(description = "Replication factor. Defaults to 1.", required = false)
          @Nullable Integer replicationFactor) {

    if (name.isBlank()) {
      throw new IllegalArgumentException(BLANK_COLLECTION_NAME_ERROR);
    }

    int effectiveShards = numShards != null ? numShards : DEFAULT_NUM_SHARDS;
    int effectiveRf = replicationFactor != null ? replicationFactor : DEFAULT_REPLICATION_FACTOR;

    String effectiveConfigSet;
    CollectionAdminRequest.Create request;
    if (configSet == null || configSet.isBlank() || configSet.equals(DEFAULT_CONFIGSET)) {
      // No collection.configName: Solr makes the private copy.
      effectiveConfigSet = name + AUTOCREATED_SUFFIX;
      request = CollectionAdminRequest.createCollection(name, effectiveShards, effectiveRf);
    } else {
      effectiveConfigSet = configSet;
      request =
          CollectionAdminRequest.createCollection(name, configSet, effectiveShards, effectiveRf);
    }
    SolrCalls.call(
        CREATE_COLLECTION_FAILED, SolrCalls.ADMIN_API_NOT_FOUND, () -> request.process(solrClient));

    return new CollectionCreationResult(name, effectiveConfigSet, effectiveShards, effectiveRf);
  }

  /**
   * MCP prompt that walks the client through a read-only inventory of the cluster: list collections
   * and characterise each by stats and health.
   *
   * @return the prompt text instructing the model how to explore collections
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpPrompt(
      name = EXPLORE_COLLECTIONS_PROMPT,
      title = "Explore Solr collections",
      description = EXPLORE_COLLECTIONS_PROMPT_DESCRIPTION)
  public GetPromptResult exploreCollectionsPrompt() {
    return McpResults.prompt(
        EXPLORE_COLLECTIONS_PROMPT_DESCRIPTION,
        Role.USER,
        """
				You are exploring an Apache Solr cluster through MCP tools. Goal: produce a concise,
				accurate picture of what already exists. This prompt is read-only; do not create or
				modify anything.

				1. List collections.
				   - Call `list-collections` to get the full set of collection names.
				   - If the user mentioned a target collection by name, note whether it appears in the
				     list.

				2. Characterize each interesting collection.
				   - For each collection the user cares about (or a small representative sample if they
				     did not name one), call `get-collection-stats` to read numDocs, segment counts, and
				     cache/handler metrics, and `check-health` to confirm the collection responds to a
				     ping and the doc count is non-zero where expected.

				3. Summarize.
				   - Tell the user which collections exist, which look healthy, and which look empty or
				     stale.
				   - If the user's intent does not match any existing collection, suggest the
				     `setup-collection` prompt to create one.
				""");
  }

  /**
   * MCP prompt that guides the client through creating a new Solr collection: validate the name,
   * pick configset / shards / replication factor, create the collection, and verify it.
   *
   * @param name desired collection name (lowercase letters, digits, underscores, hyphens — no
   *     spaces)
   * @param purpose optional one-line description of what the collection is for; may be {@code null}
   *     or blank
   * @return the prompt text instructing the model how to set up the collection
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpPrompt(
      name = SETUP_COLLECTION_PROMPT,
      title = "Set up a new Solr collection",
      description = SETUP_COLLECTION_PROMPT_DESCRIPTION)
  public GetPromptResult setupCollectionPrompt(
      @McpArg(
              name = "name",
              description =
                  "Desired collection name. Lowercase letters, digits, underscores, hyphens — no spaces.",
              required = true)
          String name,
      @McpArg(
              name = "purpose",
              description =
                  "Optional one-line description of what the collection is for (used only to ground the conversation).",
              required = false)
          @Nullable String purpose) {
    String purposeLine =
        (purpose == null || purpose.isBlank()) ? "" : "\nPurpose: " + purpose.strip() + "\n";
    return McpResults.prompt(
        SETUP_COLLECTION_PROMPT_DESCRIPTION,
        Role.USER,
        """
				You are setting up a new Solr collection named `%s` through MCP tools.%s
				1. Validate the name.
				   - Lowercase letters, digits, underscores, hyphens only — no spaces or uppercase.
				   - Call `list-collections` and confirm `%s` does not already exist. If it does, stop
				     and tell the user.

				2. Choose creation parameters.
				   - Defaults: a private copy of the `%s` configset, %d shard(s), replicationFactor %d.
				     These work for most single-node and small SolrCloud setups.
				   - Only override if the user asked: pass `configSet` to share an existing configset
				     with a pre-built schema, more shards for a large dataset, or higher
				     replicationFactor for redundancy.

				3. Create.
				   - Call `create-collection` with `name=%s` and any non-default `configSet`,
				     `numShards`, `replicationFactor` values. Report the name, configSet, numShards
				     and replicationFactor it returns.

				4. Verify.
				   - Call `list-collections` again; `%s` should appear.
				   - Call `check-health` on `%s`; it should respond to ping.

				Next step suggestion: define the schema. Use the `design-schema` prompt to design
				fields for the dataset the user wants to index.
				"""
            .formatted(
                name,
                purposeLine,
                name,
                DEFAULT_CONFIGSET,
                DEFAULT_NUM_SHARDS,
                DEFAULT_REPLICATION_FACTOR,
                name,
                name,
                name));
  }
}
