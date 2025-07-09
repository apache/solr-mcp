#!/bin/bash
set -e

# Ensure mydata directory exists
mkdir -p /mydata

# Download books.csv if not already present
if [ ! -f /mydata/books.csv ]; then
  wget -O /mydata/books.csv https://raw.githubusercontent.com/apache/solr/main/solr/example/exampledocs/books.csv
fi

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

# Create the 'books' collection in SolrCloud mode
if ! /opt/solr/bin/solr list | grep -q 'books'; then
  /opt/solr/bin/solr create -c books -n _default
fi
if ! /opt/solr/bin/solr list | grep -q 'films'; then
  /opt/solr/bin/solr create -c films -n _default
fi

# Wait for collection to be ready
until curl -s "http://localhost:8983/solr/admin/collections?action=LIST" | grep -q '"books"'; do
  echo "Waiting for books collection to be ready..."
  sleep 2
done

# Post the books.csv data
/opt/solr/bin/solr post -c books /mydata/books.csv

# Post the films.json data
/opt/solr/bin/solr post -c films /mydata/films.json

# Stop background Solr and run in foreground
/opt/solr/bin/solr stop
exec /opt/solr/bin/solr start -c -z zoo:2181 -f
