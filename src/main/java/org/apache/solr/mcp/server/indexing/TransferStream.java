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
package org.apache.solr.mcp.server.indexing;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * The body of an {@code index-url} response as it streams to Solr. Applies the
 * whole-fetch deadline to every read, counts bytes, and records whether the
 * body was read to its end, because the reader cannot be trusted to say so:
 * SolrJ's content writer logs a read failure and ends the upload early, which
 * Solr can accept as a shorter but valid document. Only this stream can tell
 * afterwards whether the whole document was sent.
 */
final class TransferStream extends FilterInputStream {

	private final long expectedLength;
	private final long deadline;
	private final Runnable onClose;
	private long count;
	private boolean ended;
	private @Nullable IOException failure;

	/**
	 * @param in
	 *            the response body
	 * @param expectedLength
	 *            the declared {@code Content-Length}, or -1 if none
	 * @param deadline
	 *            {@link System#nanoTime()} value after which a read fails with
	 *            {@link SocketTimeoutException}
	 * @param onClose
	 *            releases the underlying response
	 */
	TransferStream(InputStream in, long expectedLength, long deadline, Runnable onClose) {
		super(in);
		this.expectedLength = expectedLength;
		this.deadline = deadline;
		this.onClose = onClose;
	}

	@Override
	public int read() throws IOException {
		byte[] one = new byte[1];
		int n = read(one, 0, 1);
		return n == -1 ? -1 : one[0] & 0xff;
	}

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		int n;
		try {
			n = super.read(buffer, offset, length);
		} catch (IOException e) {
			throw record(e);
		}
		if (n == -1) {
			ended = true;
		} else {
			count += n;
		}
		if (System.nanoTime() - deadline > 0) {
			throw record(new SocketTimeoutException("total timeout exceeded while reading the body"));
		}
		return n;
	}

	@Override
	public long skip(long n) throws IOException {
		byte[] discard = new byte[(int) Math.min(n, 8192)];
		long skipped = 0;
		int read;
		while (skipped < n && (read = read(discard, 0, (int) Math.min(discard.length, n - skipped))) != -1) {
			skipped += read;
		}
		return skipped;
	}

	@Override
	public boolean markSupported() {
		return false;
	}

	/** Bytes read so far. */
	long bytesRead() {
		return count;
	}

	/** The first read failure, or {@code null} if every read so far succeeded. */
	@Nullable IOException failure() {
		return failure;
	}

	/**
	 * Confirms the whole body was read.
	 *
	 * @throws IOException
	 *             the recorded read failure, or an {@link EOFException} if the body
	 *             was not read to its end or fell short of its declared length
	 */
	void requireComplete() throws IOException {
		if (failure != null) {
			throw failure;
		}
		if (!ended) {
			throw new EOFException("the body was not read to its end");
		}
		if (expectedLength >= 0 && count != expectedLength) {
			throw new EOFException("received " + count + " of " + expectedLength + " bytes");
		}
	}

	@Override
	public void close() {
		onClose.run();
	}

	private IOException record(IOException e) {
		if (failure == null) {
			failure = e;
		}
		return e;
	}
}
