# Solr MCP Server

A Spring AI Model Context Protocol (MCP) server that provides tools for interacting with Apache Solr. This server enables AI assistants like Claude to search, index, and manage Solr collections through the MCP protocol.

## Overview

This project provides a set of tools that allow AI assistants to interact with Apache Solr, a powerful open-source search platform. By implementing the Spring AI MCP protocol, these tools can be used by any MCP-compatible client, including Claude Desktop. The project uses SolrJ, the official Java client for Solr, to communicate with Solr instances.

The server provides the following capabilities:
- Search Solr collections with advanced query options
- Index documents into Solr collections
- Manage and monitor Solr collections
- Retrieve and analyze Solr schema information

## Prerequisites

- Java 21 or higher
- Docker and Docker Compose (for running Solr)
- Gradle 8.5+ (wrapper included in project)

## Installation and Setup

### 1. Clone the repository

```bash
git clone https://github.com/yourusername/solr-mcp-server.git
cd solr-mcp-server
```

### 2. Start Solr using Docker Compose

```bash
docker-compose up -d
```

This will start a Solr instance in SolrCloud mode with ZooKeeper and create two sample collections:
- `books` - A collection with sample book data
- `films` - A collection with sample film data

### 3. Build the Project

This project uses Gradle with version catalogs for dependency management. All dependencies and their versions are
centrally managed in `gradle/libs.versions.toml`.

```bash
# Build the project and run tests
./gradlew build

# Build without tests (faster)
./gradlew assemble

# Clean and rebuild
./gradlew clean build
```

The build produces two JAR files in `build/libs/`:

- `solr-mcp-server-0.0.1-SNAPSHOT.jar` - Executable JAR with all dependencies (fat JAR)
- `solr-mcp-server-0.0.1-SNAPSHOT-plain.jar` - Plain JAR without dependencies

## Available Tools

The server provides the following tools that can be used by MCP clients:

### 1. Search

Search a Solr collection with advanced query options.

```
Tool: Search
Description: Search specified Solr collection with query, optional filters, facets, sorting, and pagination.
Parameters:
  - collection: Solr collection to query
  - query: Solr q parameter (defaults to "*:*" if not specified)
  - filterQueries: Solr fq parameter (optional)
  - facetFields: Solr facet fields (optional)
  - sortClauses: Solr sort parameter (optional)
  - start: Starting offset for pagination (optional)
  - rows: Number of rows to return (optional)
```

### 2. Index Documents

Index JSON documents into a Solr collection.

```
Tool: index_documents
Description: Index documents from JSON string into Solr collection
Parameters:
  - collection: Solr collection to index into
  - json: JSON string containing documents to index
```

### 3. List Collections

List all available Solr collections.

```
Tool: listCollections
Description: List solr collections
Parameters: None
```

### 4. Get Collection Stats

Get detailed statistics and metrics for a Solr collection.

```
Tool: getCollectionStats
Description: Get stats/metrics on a Solr collection
Parameters:
  - collection: Name of the collection
```

### 5. Check Collection Health

Check the health status of a Solr collection.

```
Tool: checkHealth
Description: Check health of a Solr collection
Parameters:
  - collection: Name of the collection
```

### 6. Get Schema

Retrieve the schema for a Solr collection.

```
Tool: getSchema
Description: Get schema for a Solr collection
Parameters:
  - collection: Name of the collection
```

## Adding to Claude Desktop

To add this MCP server to Claude Desktop:

1. Build the project as a standalone JAR:

```bash
./gradlew build
```

2. In Claude Desktop, go to Settings > Developer > Edit Config

3. Add the following configuration to your MCP settings:

```json
{
    "mcpServers": {
        "solr-search-mcp": {
            "command": "java",
            "args": [
                "-jar",
                "/absolute/path/to/solr-mcp-server/build/libs/solr-mcp-server-0.0.1-SNAPSHOT.jar"
            ],
            "env": {
                "SOLR_URL": "http://localhost:8983/solr/"
            }
        }
  }
}
```

**Note:** Replace `/absolute/path/to/solr-mcp-server` with the actual path to your project directory.

## Testing with MCP Inspector

For development and testing, you can use the [MCP Inspector](https://github.com/modelcontextprotocol/inspector):

```bash
# Install the MCP Inspector (requires Node.js)
npx @modelcontextprotocol/inspector java -jar build/libs/solr-mcp-server-0.0.1-SNAPSHOT.jar
```

This provides a web interface to test MCP tools interactively.

## Usage Examples

Here are some examples of how to use the tools from an MCP client like Claude:

### Searching for Books

```
I need to search for fantasy books in the Solr collection. Can you help me?
```

Claude can use the Search tool to query the books collection:

```
I'll search for fantasy books in the Solr collection.

Using the Search tool with:
- collection: books
- query: genre_s:fantasy
- rows: 5

Here are the fantasy books I found:
1. "A Game of Thrones" by George R.R. Martin
2. "A Clash of Kings" by George R.R. Martin
...
```

### Indexing New Documents

```
I have some new book data I'd like to add to Solr. Can you help me index it?
```

Claude can use the index_documents tool:

```
I'll help you index that book data into Solr.

Using the index_documents tool with:
- collection: books
- json: [{"id":"123456789","name":["The New Book"],"author":["Jane Author"],"genre_s":"mystery","price":[12.99]}]

The document has been successfully indexed into the books collection.
```

### Listing Collections

```
What Solr collections are available in the system?
```

Claude can use the listCollections tool:

```
I'll check what Solr collections are available.

Using the listCollections tool.

The following collections are available:
1. books
2. films
```

### Getting Collection Statistics

```
Can you tell me more about the 'books' collection? I'd like to know its size and other metrics.
```

Claude can use the getCollectionStats tool:

```
I'll get statistics for the 'books' collection.

Using the getCollectionStats tool with:
- collection: books

Here are the statistics for the 'books' collection:
- Number of documents: 1,245
- Segment count: 5
- Query performance:
  - Average query time: 12ms
  - Cache hit ratio: 0.85
- Handler statistics:
  - Select handler requests: 1,532
  - Update handler requests: 245
```

### Checking Collection Health

```
Is the 'films' collection healthy and responding properly?
```

Claude can use the checkHealth tool:

```
I'll check the health of the 'films' collection.

Using the checkHealth tool with:
- collection: films

Health status:
- Healthy: Yes
- Response time: 8ms
- Total documents: 532
- Last checked: 2023-06-15 14:32:45
```

### Retrieving Schema Information

```
What fields are defined in the 'books' collection schema?
```

Claude can use the getSchema tool:

```
I'll retrieve the schema for the 'books' collection.

Using the getSchema tool with:
- collection: books

The 'books' collection has the following fields:
- id (string): Unique identifier
- name (text_general): Book title
- author (text_general): Book author
- genre_s (string): Genre
- price (float): Book price
- in_stock_b (boolean): Whether the book is in stock
- publish_date (date): Publication date
```

## Troubleshooting

If you encounter issues:

1. Ensure Solr is running and accessible. By default, the server connects to http://localhost:8983/solr/, but you can set the `SOLR_URL` environment variable to point to a different Solr instance.
2. Check the logs for any error messages
3. Verify that the collections exist using the Solr Admin UI

## License

This project is licensed under the Apache License 2.0.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
