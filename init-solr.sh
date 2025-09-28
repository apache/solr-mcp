#!/bin/bash
set -e

# Ensure mydata directory exists
mkdir -p /mydata

# Download books.csv if not already present
if [ ! -f /mydata/books.csv ]; then
  wget -O /mydata/books.csv https://raw.githubusercontent.com/apache/solr/main/solr/example/exampledocs/books.csv
fi

# Download films.json if not already present
if [ ! -f /mydata/films.json ]; then
  wget -O /mydata/films.json https://raw.githubusercontent.com/apache/solr/refs/heads/main/solr/example/films/films.json
fi


# Start Solr in background
/opt/solr/bin/solr start -c -z zoo:2181

# Wait for Solr to be up
until curl -s http://localhost:8983/solr/ > /dev/null; do
  echo "Waiting for Solr to start..."
  sleep 2
done

# Wait for ZooKeeper to be fully ready for SolrCloud operations
echo "Waiting for ZooKeeper to be ready for SolrCloud operations..."
sleep 10

# Wait for cluster state to be ready
until curl -s "http://localhost:8983/solr/admin/collections?action=CLUSTERSTATUS" | grep -q '"responseHeader"'; do
  echo "Waiting for SolrCloud cluster to be ready..."
  sleep 2
done

# Create the 'books' collection in SolrCloud mode
# Check if collection already exists via Collections API
if ! curl -s "http://localhost:8983/solr/admin/collections?action=LIST" | grep -q '"books"'; then
  echo "Creating books collection..."
  /opt/solr/bin/solr create -c books -n _default || {
    echo "First attempt failed, retrying collection creation..."
    sleep 5
    /opt/solr/bin/solr create -c books -n _default || {
      echo "Collection creation failed twice, but continuing..."
    }
  }
else
  echo "Books collection already exists, skipping creation."
fi


# Wait for collection to be ready
until curl -s "http://localhost:8983/solr/admin/collections?action=LIST" | grep -q '"books"'; do
  echo "Waiting for books collection to be ready..."
  sleep 2
done

# Create the 'films' collection in SolrCloud mode
# Check if collection already exists via Collections API
if ! curl -s "http://localhost:8983/solr/admin/collections?action=LIST" | grep -q '"films"'; then
  echo "Creating films collection..."
  /opt/solr/bin/solr create -c films -n _default || {
    echo "First attempt failed, retrying collection creation..."
    sleep 5
    /opt/solr/bin/solr create -c films -n _default || {
      echo "Collection creation failed twice, but continuing..."
    }
  }
else
  echo "Films collection already exists, skipping creation."
fi

# Wait for films collection to be ready
until curl -s "http://localhost:8983/solr/admin/collections?action=LIST" | grep -q '"films"'; do
  echo "Waiting for films collection to be ready..."
  sleep 2
done

# Post the books.csv data
/opt/solr/bin/solr post -c books /mydata/books.csv

# Post the films.json data
/opt/solr/bin/solr post -c films /mydata/films.json


# Stop background Solr and run in foreground
/opt/solr/bin/solr stop
exec /opt/solr/bin/solr start -c -z zoo:2181 -f
